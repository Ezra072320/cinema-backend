"""Real local file -> Alloy -> Loki checks, with optional tracing correlation and observer outages.

Requires the observability group by default, or logs with --without-agent.
Uses existing administrator login and read-only business requests. The optional
SSE probe creates and removes one temporary user, without orders or schedules.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import http.cookiejar
import json
import secrets as random_secrets
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from ai_local_acceptance import Acceptance, ROOT
from tracing_local_acceptance import fetch, sampling_header, wait, TRACE_SERVICES, BASE, UI

LOKI = 'http://127.0.0.1:13100'
GRAFANA = 'http://127.0.0.1:13000'
COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
    '-f', 'deploy/local/compose.transactions.yml', '-f', 'deploy/local/compose.search.yml',
    '-f', 'deploy/local/compose.metrics.yml', '-f', 'deploy/local/compose.tracing.yml',
    '-f', 'deploy/local/compose.logs.yml', '-f', 'deploy/local/compose.observe.yml',
    '--profile', 'messaging', '--profile', 'transactions', '--profile', 'search',
    '--profile', 'metrics', '--profile', 'tracing', '--profile', 'logs']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.work/logs-acceptance')
    parser.add_argument('--without-agent', action='store_true')
    parser.add_argument('--outage', action='store_true')
    parser.add_argument('--sse-probe', action='store_true', help='Create one temporary user, run actual Qwen SSE, then remove only that user data')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    secrets = [value for key, value in settings.items() if len(value) >= 8 and
               any(part in key for part in ('PASSWORD', 'SECRET', 'TOKEN', 'KEY', '_AK', '_SK'))]
    public = urllib.request.build_opener()
    horizon = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    authorization = 'Basic ' + base64.b64encode(('cinema:' + settings['GRAFANA_ADMIN_PASSWORD']).encode()).decode()
    report = {'passed': [], 'completed': False, 'without_agent': args.without_agent}
    paused = set()

    def passed(message):
        report['passed'].append(message)
        print('PASS ' + message, flush=True)

    def query(selector):
        parameters = {'query': selector, 'start': str((time.time_ns() - 1800 * 10**9)),
                      'end': str(time.time_ns()), 'direction': 'forward', 'limit': 1000}
        data = fetch(public, LOKI + '/loki/api/v1/query_range?' + urllib.parse.urlencode(parameters))
        assert data.get('status') == 'success', 'Loki query failed'
        records = []
        for stream in data['data']['result']:
            for timestamp, line in stream['values']:
                records.append({'timestamp': timestamp, 'labels': stream['stream'], 'record': json.loads(line)})
        return records

    def logs_for(probe):
        records = query('{job="cinema"} |= "' + probe['request_id'] + '"')
        records = [row for row in records if row['record'].get('event') == 'http.request']
        return records if TRACE_SERVICES <= {row['record']['service'] for row in records} else None

    def request_probe():
        trace, header = sampling_header()
        request_id = 'logs-' + uuid.uuid4().hex
        marker = 'private-query-' + uuid.uuid4().hex
        headers = {'X-Trace-Id': request_id}
        if not args.without_agent:
            headers['sw8'] = header
        result = fetch(public, BASE + '/api/movie/topSales?acceptanceSecret=' + marker, headers=headers)
        assert result.get('code') == 1, 'Business read failed'
        return {'trace_id': trace if not args.without_agent else '', 'request_id': request_id, 'marker': marker}

    def verify_records(probe, records):
        encoded = json.dumps(records, ensure_ascii=False)
        assert probe['marker'] not in encoded and all(secret not in encoded for secret in secrets), 'Sensitive data reached Loki'
        for row in records:
            record = row['record']
            assert record['trace_id'] == probe['trace_id'], 'Log trace context leaked or was lost'
            assert record['request_id'] == probe['request_id']
            assert record['status'] == 200 and record['duration_ms'] >= 0
            assert '?' not in record['route'], 'Query captured as route'
            assert not ({'trace_id', 'request_id', 'order_id', 'xid'} & set(row['labels'])), 'High-cardinality index label'

    def command(*parts):
        result = subprocess.run(COMPOSE + list(parts), cwd=ROOT, capture_output=True)
        assert result.returncode == 0, 'Observer operation failed: ' + parts[0]

    def stop(role):
        paused.add(role)
        command('stop', role)

    def restore(role):
        if role in paused:
            command('start', '--wait', '--wait-timeout', '150', role)
            paused.remove(role)

    try:
        mount = subprocess.run(['docker', 'inspect', 'cinema-cloud-alloy-1', '--format', '{{json .Mounts}}'],
                               cwd=ROOT, capture_output=True, text=True)
        assert mount.returncode == 0
        mounts = json.loads(mount.stdout)
        assert any(item['Destination'] == '/var/log/cinema' and not item['RW'] for item in mounts)
        assert not any('docker.sock' in item['Destination'] for item in mounts)
        passed('Alloy reads only the dedicated application log volume as read-only; no Docker socket is mounted')
        health = fetch(public, GRAFANA + '/api/datasources/uid/cinema-loki/health', headers={'Authorization': authorization})
        assert health.get('status') == 'OK', 'Grafana Loki datasource unavailable'
        datasource = fetch(public, GRAFANA + '/api/datasources/uid/cinema-loki', headers={'Authorization': authorization})
        fields = datasource.get('jsonData', {}).get('derivedFields', [])
        assert any('18080/layer/general/trace' in field.get('url', '') and '${__value.raw}' in field.get('url', '') for field in fields)
        passed('Grafana Loki datasource is healthy and provisions the SkyWalking trace link')
        probe = request_probe()
        records = wait(lambda: logs_for(probe), 'three linked service access logs', 150)
        verify_records(probe, records)
        report['request_id'] = probe['request_id']
        report['trace_id'] = probe['trace_id']
        (args.output / 'linked-logs.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
        passed('one real read produces Gateway, Catalog and internal Trade logs with the same request and trace context, without query values or credentials')
        if not args.without_agent:
            fetch(horizon, UI + '/api/auth/login', {'username': 'cinema', 'password': settings['SKYWALKING_UI_PASSWORD']})
            def linked_trace():
                data = fetch(horizon, UI + '/api/trace/' + probe['trace_id'])
                spans = data.get('native', {}).get('spans', [])
                return spans if TRACE_SERVICES <= {span['serviceCode'] for span in spans} else None
            spans = wait(linked_trace, 'same trace in Horizon', 150)
            assert {record['record']['trace_id'] for record in records} == {span['traceId'] for span in spans}
            components = {span.get('component', '') for span in spans}
            assert 'Feign' in components and any('Hikari' in component for component in components), 'Feign/JDBC trace spans missing'
            (args.output / 'linked-trace.json').write_text(json.dumps(spans, ensure_ascii=False, indent=2), encoding='utf-8')
            passed('Loki logs resolve to the same actual Gateway/Catalog/Feign/Trade trace in Horizon')
        with ThreadPoolExecutor(max_workers=4) as executor:
            probes = list(executor.map(lambda _: request_probe(), range(4)))
        for item in probes:
            linked = wait(lambda: logs_for(item), 'isolated concurrent request', 150)
            verify_records(item, linked)
        passed('four concurrent real requests keep their own correlation IDs across callback threads and services')
        fixture = Acceptance(BASE, args.output)
        captcha = fixture.captcha()
        auth_trace, auth_header = sampling_header()
        auth_request = 'auth-' + uuid.uuid4().hex
        headers = {'X-Trace-Id': auth_request, **({'sw8': auth_header} if not args.without_agent else {})}
        login = fetch(public, BASE + '/api/admin/login',
                      {'name': 'admin', 'password': settings['INITIAL_ADMIN_PASSWORD'], **captcha}, headers)
        assert login.get('code') == 1 and isinstance(login.get('data'), str), 'Actual administrator login failed'
        token = login['data']
        read_request = 'auth-read-' + uuid.uuid4().hex
        result = fetch(public, BASE + '/api/admin/info', headers={'Authorization': token, 'X-Trace-Id': read_request})
        assert result.get('code') == 1
        def authentication_logs():
            rows = query('{job="cinema"} |= "' + auth_request + '"') + query('{job="cinema"} |= "' + read_request + '"')
            return rows if {'cinema-identity', 'cinema-gateway'} <= {row['record']['service'] for row in rows} else None
        authenticated = wait(authentication_logs, 'actual administrator authentication logs', 150)
        encoded = json.dumps(authenticated, ensure_ascii=False)
        assert token not in encoded and captcha['captchaTicket'] not in encoded and all(secret not in encoded for secret in secrets)
        assert not any(key.lower() in ('password', 'authorization', 'captchacode', 'body', 'headers')
                       for row in authenticated for key in row['record'])
        passed('real captcha/password login and JWT-protected read reach Loki without credentials, raw headers or request bodies')
        if args.sse_probe:
            temporary = Acceptance(BASE, args.output)
            try:
                password = random_secrets.token_hex(7)
                temporary.http('/user/register', {'name': temporary.name, 'password': password, 'age': 24, **temporary.captcha()})
                temporary.uid = int(temporary.sql("SELECT id FROM cinema_identity.user WHERE name='" + temporary.name + "';"))
                temporary.token = temporary.http('/user/login', {'name': temporary.name, 'password': password, **temporary.captcha()})
                stream_trace, stream_header = sampling_header()
                stream_request = 'sse-' + uuid.uuid4().hex
                request = urllib.request.Request(BASE + '/api/ai/chat', method='POST',
                    headers={'Content-Type': 'application/json', 'Authorization': temporary.token,
                             'X-Trace-Id': stream_request, **({'sw8': stream_header} if not args.without_agent else {})},
                    data=json.dumps({'chatId': temporary.chat, 'question': '请查询我的个人资料，只告诉我当前年龄。'}).encode())
                with urllib.request.urlopen(request, timeout=120) as response:
                    assert response.status == 200 and 'text/event-stream' in response.headers['Content-Type']
                    raw = response.read().decode('utf-8')
                frames = [json.loads(line[5:].strip()) for line in raw.splitlines() if line.startswith('data:')]
                assert any(isinstance(frame, dict) and frame.get('type') == 'done' for frame in frames), 'SSE did not persist and complete'
                assert not any(isinstance(frame, dict) and frame.get('type') == 'error' for frame in frames), 'Qwen SSE failed'
                assert '24' in ''.join(frame for frame in frames if isinstance(frame, str)), 'Actual profile query did not return the fixture age'
                def stream_logs():
                    rows = query('{job="cinema"} |= "' + stream_request + '"')
                    rows = [row for row in rows if row['record'].get('event') == 'http.request'
                            and row['record']['service'] in ('cinema-gateway', 'cinema-ai')]
                    return rows if {'cinema-gateway', 'cinema-ai'} <= {row['record']['service'] for row in rows} else None
                completed = wait(stream_logs, 'completed SSE access logs', 150)
                assert len(completed) == 2, 'SSE completion logged more than once'
                assert all(row['record']['status'] == 200 and row['record']['trace_id'] ==
                           (stream_trace if not args.without_agent else '') for row in completed), 'SSE correlation lost'
                assert temporary.token not in json.dumps(completed) and password not in json.dumps(completed), 'SSE credentials captured'
                (args.output / 'sse-logs.json').write_text(json.dumps(completed, ensure_ascii=False, indent=2), encoding='utf-8')
                report['sse_trace_id'] = stream_trace if not args.without_agent else ''
                passed('actual Qwen profile SSE reaches done; Gateway and AI each record one completed request with the captured correlation context')
            finally:
                temporary.cleanup()
                report['sse_fixture_cleaned'] = True
        if args.outage:
            stop('loki')
            offline = request_probe()
            restore('loki')
            recovered = wait(lambda: logs_for(offline), 'Loki outage retry', 180)
            verify_records(offline, recovered)
            assert logs_for(probe), 'Previously stored log lost after Loki restart'
            passed('business reads survive Loki downtime; Alloy retries the new records and existing records remain queryable after restart')
            stop('alloy')
            unread = request_probe()
            restore('alloy')
            replayed = wait(lambda: logs_for(unread), 'file tail after Alloy restart', 180)
            verify_records(unread, replayed)
            passed('business reads survive Alloy downtime; the same collector resumes retained application files after restart')
        report['completed'] = True
    finally:
        try:
            restore('loki')
            restore('alloy')
        finally:
            (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Logs acceptance completed=' + str(report['completed']))


if __name__ == '__main__':
    main()
