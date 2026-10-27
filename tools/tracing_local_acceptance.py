"""Local SkyWalking HTTP/Feign/JDBC acceptance; no business data is created.

Uses the private Horizon login, samples read-only requests, checks trace links and
data minimisation, and optionally restarts OAP/UI to verify business isolation.
Run after tools/local-stack.ps1 -Action Up -Group tracing.
"""
import argparse
import base64
import http.cookiejar
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from ai_local_acceptance import Acceptance, ROOT

UI = 'http://127.0.0.1:18080'
ES = 'http://127.0.0.1:19200'
BASE = 'http://localhost:8088'
TRACE_SERVICES = {'cinema-gateway', 'cinema-catalog', 'cinema-trade'}
COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
           '-f', 'deploy/local/compose.search.yml', '-f', 'deploy/local/compose.tracing.yml',
           '--profile', 'messaging', '--profile', 'search', '--profile', 'tracing']


def fetch(opener, url, body=None, headers=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
                                 headers={'Content-Type': 'application/json', **(headers or {})})
    with opener.open(req, timeout=30) as response:
        return json.load(response)


def sampling_header():
    # Supply a synthetic upstream reference so each probe has a known, sampled ID.
    trace = 'cinema.accept.' + uuid.uuid4().hex
    encode = lambda value: base64.b64encode(value.encode()).decode()
    sw8 = '-'.join(['1', encode(trace), encode(uuid.uuid4().hex), '0',
                    encode('cinema-local-acceptance'), encode('single-probe'),
                    encode('read-only'), encode('gateway:8080')])
    return trace, sw8


def sampled_request(path='/movie/topSales?limit=5'):
    trace, sw8 = sampling_header()
    result = fetch(urllib.request.build_opener(), BASE + '/api' + path,
                   headers={'sw8': sw8, 'X-Trace-Id': 'accept-' + uuid.uuid4().hex})
    assert result.get('code') == 1, 'Read-only business request failed'
    return trace


def wait(check, label, timeout=100):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = check()
        if value:
            return value
        time.sleep(2)
    raise AssertionError('Timed out: ' + label)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.work/tracing-acceptance')
    parser.add_argument('--outage', action='store_true', help='Stop and restore only the two tracing observers')
    parser.add_argument('--browser', action='store_true', help='Verify the actual Horizon login and trace page in Chrome')
    parser.add_argument('--auth-probe', action='store_true', help='Compile a tiny Java 21 probe and verify invalid agent authentication')
    parser.add_argument('--application-auth-only', action='store_true', help='Only verify actual administrator password/captcha login and JWT header privacy')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    secrets = [v for k, v in settings.items() if len(v) >= 8 and
               any(part in k for part in ('PASSWORD', 'SECRET', 'TOKEN', 'KEY', '_AK', '_SK'))]
    report = {'passed': [], 'completed': False, 'page_errors': []}
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    public_opener = urllib.request.build_opener()

    def passed(message):
        report['passed'].append(message)
        print('PASS ' + message, flush=True)

    def login():
        fetch(opener, UI + '/api/auth/login', {'username': 'cinema', 'password': settings['SKYWALKING_UI_PASSWORD']})
        me = fetch(opener, UI + '/api/auth/me')
        assert me.get('username') == 'cinema', 'Private Horizon login failed'

    def trace_spans(trace_id):
        data = fetch(opener, UI + '/api/trace/' + trace_id)
        native = data.get('native', {})
        assert native.get('reachable'), 'OAP trace query unavailable'
        return native.get('spans', [])

    def full_trace(trace_id):
        spans = trace_spans(trace_id)
        return spans if TRACE_SERVICES <= {s['serviceCode'] for s in spans} else None

    def index_snapshot():
        indexes = fetch(public_opener, ES + '/_cat/indices/cinema-movies-*?format=json&h=index,docs.count')
        aliases = fetch(public_opener, ES + '/cinema-movies-*/_alias')
        return {'indexes': sorted((i['index'], i['docs.count']) for i in indexes), 'aliases': aliases}

    def command(*args):
        result = subprocess.run([*COMPOSE, *args], cwd=ROOT, capture_output=True)
        assert result.returncode == 0, 'Tracing observer operation failed: ' + args[0]

    stopped = False
    try:
        for role in ('identity', 'catalog', 'trade', 'ai', 'gateway'):
            state = subprocess.run(['docker', 'inspect', '--format',
                                    '{{.State.Health.Status}} {{range .Mounts}}{{.Destination}} {{end}}',
                                    'cinema-cloud-' + role + '-1'], capture_output=True, text=True)
            assert state.returncode == 0 and state.stdout.startswith('healthy ') and '/opt/skywalking' in state.stdout, \
                'Finish tracing startup before acceptance: ' + role
        for path in ('/api/auth/me', '/api/trace/nonexistent', '/api/oap/info'):
            try:
                fetch(opener, UI + path)
            except urllib.error.HTTPError as error:
                assert error.code == 401, 'Unexpected anonymous Horizon status'
            else:
                raise AssertionError('Anonymous Horizon data access allowed')
        login()
        passed('Horizon requires its private login for identity, trace and OAP APIs')
        if args.application_auth_only:
            fixture = Acceptance(BASE, args.output)
            captcha = fixture.captcha()
            login_trace, login_header = sampling_header()
            result = fetch(public_opener, BASE + '/api/admin/login',
                           {'name': 'admin', 'password': settings['INITIAL_ADMIN_PASSWORD'], **captcha},
                           {'sw8': login_header})
            assert result.get('code') == 1 and isinstance(result.get('data'), str), 'Actual administrator login failed'
            token = result['data']
            secrets.extend([token, *captcha.values()])
            info_trace, info_header = sampling_header()
            info = fetch(public_opener, BASE + '/api/admin/info', headers={'Authorization': token, 'sw8': info_header})
            assert info.get('code') == 1, 'Actual JWT-protected administrator read failed'
            report['login_trace_id'] = login_trace
            report['identity_read_trace_id'] = info_trace

            def authenticated_trace(trace_id):
                spans = trace_spans(trace_id)
                return spans if {'cinema-gateway', 'cinema-identity'} <= {s['serviceCode'] for s in spans} else None

            traces = [wait(lambda: authenticated_trace(t), 'authenticated Identity trace') for t in (login_trace, info_trace)]
            encoded = json.dumps(traces, ensure_ascii=False)
            assert all(secret not in encoded for secret in secrets), 'Authentication credential captured'
            for spans in traces:
                for span in spans:
                    assert not any(t['key'] in ('http.headers', 'http.params', 'http.body', 'http.request.body', 'db.sql.parameters') for t in span['tags']), 'Authentication payload collection enabled'
            (args.output / 'authenticated-traces.json').write_text(json.dumps(traces, ensure_ascii=False, indent=2), encoding='utf-8')
            passed('actual administrator password/captcha login and JWT-protected read produce linked traces without password, captcha, JWT or SQL bind values')
            report['completed'] = True
            return
        health_started_ms = int(time.time() * 1000)
        health_endpoints = []
        for role in ('identity', 'catalog', 'trade', 'ai', 'gateway'):
            # Use real health-check semantics: no sampled upstream sw8 header.
            # An explicitly sampled upstream context intentionally overrides a
            # local sampling decision and is not how Docker probes this endpoint.
            health = subprocess.run(['docker', 'compose', 'exec', '-T', role, 'curl', '-fsS',
                                     'http://localhost:8080/actuator/health/readiness'],
                                    cwd=ROOT, capture_output=True, text=True)
            assert health.returncode == 0 and json.loads(health.stdout)['status'] == 'UP', 'Health ignore probe failed: ' + role
            service_id = base64.b64encode(('cinema-' + role).encode()).decode() + '.1'
            endpoint = ('GET:' if role != 'gateway' else '') + '/actuator/health/readiness'
            health_endpoints.append(service_id + '_' + base64.b64encode(endpoint.encode()).decode())
        if args.auth_probe:
            directory = args.output.resolve() / 'auth-probe'
            directory.mkdir(parents=True, exist_ok=True)
            compile_result = subprocess.run(['javac', '--release', '21', '-d', str(directory),
                                              str(ROOT / 'tools/SkyWalkingAuthProbe.java')], capture_output=True)
            assert compile_result.returncode == 0, 'Java 21 auth probe compilation failed'
            probe = subprocess.run(['docker', 'run', '--rm', '--network', 'cinema-cloud_cinema', '--memory', '256m',
                                    '--mount', 'type=bind,source=' + str(ROOT / '.work/skywalking/mvc-9.8.0') + ',target=/opt/skywalking,readonly',
                                    '--mount', 'type=bind,source=' + str(directory) + ',target=/probe,readonly',
                                    '-e', 'SW_AGENT_NAME=cinema-rejected-probe',
                                    '-e', 'SW_AGENT_COLLECTOR_BACKEND_SERVICES=skywalking-oap:11800',
                                    '-e', 'SW_AGENT_AUTHENTICATION=invalid-acceptance-token',
                                    '-e', 'SW_LOGGING_OUTPUT=CONSOLE', '-e', 'SW_LOGGING_LEVEL=WARN',
                                    '--entrypoint', 'java', 'cinema/identity:local', '-Xmx80m', '-XX:ActiveProcessorCount=1',
                                    '-javaagent:/opt/skywalking/skywalking-agent.jar', '-cp', '/probe', 'SkyWalkingAuthProbe'],
                                   capture_output=True, text=True, encoding='utf-8', timeout=70)
            probe_log = probe.stdout + probe.stderr
            assert probe.returncode == 0 and 'PERMISSION_DENIED' in probe_log, 'Invalid agent token was not explicitly denied'
            (directory / 'denied.log').write_text(probe_log, encoding='utf-8')
            services = fetch(opener, UI + '/api/layer/general/services')
            assert services.get('reachable') and 'cinema-rejected-probe' not in {s['name'] for s in services['services']}, 'Rejected agent registered'
            passed('a real Java agent with an incorrect token receives PERMISSION_DENIED and does not register a service')
        before = index_snapshot()
        trace_id = sampled_request('/movie/topSales?limit=5&acceptanceSecret=probe-do-not-collect')
        report['http_trace_id'] = trace_id
        spans = wait(lambda: full_trace(trace_id), 'Gateway/Catalog/Trade trace')
        report['http_services'] = sorted({s['serviceCode'] for s in spans})
        report['http_components'] = sorted({s['component'] for s in spans if s.get('component')})
        assert all(s['traceId'] == trace_id for s in spans), 'Trace IDs differ across applications'
        assert any(s['component'] == 'Feign' for s in spans), 'Actual Feign span missing'
        assert any(s['layer'] == 'Database' and s['serviceCode'] == 'cinema-trade' for s in spans), 'Trade JDBC span missing'
        by_segment = {s['segmentId']: s['serviceCode'] for s in spans}
        edges = {(by_segment.get(r['parentSegmentId']), s['serviceCode']) for s in spans for r in s['refs']
                 if r['type'] == 'CROSS_PROCESS' and r['parentSegmentId'] in by_segment}
        assert {('cinema-gateway', 'cinema-catalog'), ('cinema-catalog', 'cinema-trade')} <= edges, 'Cross-process parent links missing'
        report['http_edges'] = sorted(edges)
        passed('one actual read has linked Gateway -> Catalog -> Feign -> Trade -> JDBC spans under the same trace ID')
        encoded = json.dumps(spans)
        assert 'probe-do-not-collect' not in encoded, 'HTTP query value collected'
        assert all(secret not in encoded for secret in secrets), 'Private credential collected in trace'
        for span in spans:
            assert not any(t['key'] in ('db.sql.parameters', 'http.params', 'http.headers') for t in span['tags']), 'Sensitive trace parameters enabled'
        (args.output / 'http-trace.json').write_text(json.dumps(spans, ensure_ascii=False, indent=2), encoding='utf-8')
        passed('sampled trace omits HTTP query values, SQL bind parameters and configured private credentials')
        if args.browser:
            from playwright.sync_api import sync_playwright, expect
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel='chrome', headless=True)
                page = browser.new_page(viewport={'width': 1500, 'height': 1000})
                page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
                page.goto(UI, wait_until='domcontentloaded')
                page.locator('input[name="username"]').fill('cinema')
                page.locator('input[type="password"]').fill(settings['SKYWALKING_UI_PASSWORD'])
                page.get_by_role('button', name='Sign in', exact=False).click()
                page.wait_for_url(lambda url: 'login' not in url, timeout=30000)
                expect(page.locator('body')).not_to_contain_text('Sign in to continue')
                page.screenshot(path=str(args.output / 'horizon-home.png'), full_page=True)
                report['browser_home'] = page.url
                report['console_errors'] = []
                page.on('console', lambda message: report['console_errors'].append(message.text) if message.type == 'error' else None)
                with page.expect_response(lambda response: '/api/trace/' + trace_id in response.url, timeout=30000) as response:
                    page.goto(UI + '/layer/general/trace?traceId=' + trace_id, wait_until='domcontentloaded')
                assert response.value.status == 200 and response.value.json()['native']['spans'], 'Browser trace detail failed'
                for service in TRACE_SERVICES:
                    expect(page.get_by_text(service, exact=True).first).to_be_visible(timeout=30000)
                page.screenshot(path=str(args.output / 'horizon-trace.png'), full_page=True)
                report['browser_trace_url'] = page.url
                assert not report['page_errors'], 'Horizon page script errors'
                assert not report['console_errors'], 'Horizon trace console errors'
                browser.close()
            passed('real Chrome private login opens the actual three-service trace waterfall without console or page script errors')
        if args.outage:
            stopped = True
            command('stop', 'skywalking-ui', 'skywalking-oap')
            sampled_request()
            passed('read-only business chain stays available while both optional tracing observers are stopped')
            command('start', '--wait', '--wait-timeout', '240', 'skywalking-oap', 'skywalking-ui')
            stopped = False
            login()
            recovery_ids = []
            last_probe = 0

            def recovered_trace():
                nonlocal last_probe
                for candidate in recovery_ids:
                    if full_trace(candidate):
                        return candidate
                if time.monotonic() - last_probe >= 10:
                    recovery_ids.append(sampled_request())
                    report['recovery_probe_ids'] = list(recovery_ids)
                    last_probe = time.monotonic()
                return None

            # OAP HTTP readiness does not imply agents' asynchronous gRPC channels
            # have reconnected. Use bounded real reads to observe that recovery.
            recovered = wait(recovered_trace, 'trace after OAP/UI restart', 180)
            assert full_trace(trace_id), 'Previously persisted trace lost after OAP restart'
            report['recovery_trace_id'] = recovered
            report['recovery_probe_count'] = len(recovery_ids)
            passed('OAP/UI restart recovers new traces and retains the original trace in Elasticsearch')
        assert index_snapshot() == before, 'Business search indexes or aliases changed'
        health_records = fetch(public_opener, ES + '/cinema-observe_segment-*/_count', {'query': {'bool': {'filter': [
            {'terms': {'endpoint_id': health_endpoints}}, {'range': {'start_time': {'gte': health_started_ms}}}]}}})
        assert health_records['count'] == 0, 'Ordinary readiness health checks still create stored traces'
        report['health_probe_started_ms'] = health_started_ms
        report['health_trace_count'] = health_records['count']
        passed('all five ordinary readiness probes stay healthy and produce no stored health traces in the measured interval')
        indexes = fetch(public_opener, ES + '/_cat/indices/cinema-observe*?format=json&h=index,docs.count')
        assert any('segment' in i['index'] and int(i['docs.count']) > 0 for i in indexes), 'No persisted SkyWalking segments'
        report['observe_index_count'] = len(indexes)
        passed('SkyWalking writes its isolated cinema-observe indexes; business search index counts and aliases remain unchanged')
        report['completed'] = True
    except Exception as error:
        report['failure'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        try:
            if stopped:
                command('start', '--wait', '--wait-timeout', '240', 'skywalking-oap', 'skywalking-ui')
        except Exception as error:
            report['restore_failure'] = str(error)
            report['completed'] = False
            raise
        finally:
            encoded = json.dumps(report, ensure_ascii=False, indent=2)
            for secret in secrets:
                encoded = encoded.replace(secret, '<REDACTED>')
            (args.output / 'report.json').write_text(encoded, encoding='utf-8')


if __name__ == '__main__':
    main()
