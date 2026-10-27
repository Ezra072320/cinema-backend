"""Read-only business probes, private metrics authentication and local observer recovery.

Temporarily stops Trade only after checking that no global transaction is pending.
Always restarts the same container. No accounts, orders or external messages created.
"""
import argparse
import base64
import json
import re
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from ai_local_acceptance import Acceptance, ROOT
from playwright.sync_api import sync_playwright, expect

ROLES = ('gateway', 'identity', 'catalog', 'trade', 'ai', 'notify', 'benefit')
COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
           '-f', 'deploy/local/compose.transactions.yml', '-f', 'deploy/local/compose.metrics.yml',
           '--profile', 'messaging', '--profile', 'transactions', '--profile', 'metrics']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.work/metrics-acceptance')
    parser.add_argument('--default-off-role', choices=ROLES,
                        help='Check one application restarted without the metrics overlay; no outage experiments')
    args = parser.parse_args()
    a = Acceptance('http://localhost:8088', args.output)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    secret = settings['CINEMA_METRICS_KEY']
    grafana_auth = 'Basic ' + base64.b64encode(('cinema:' + settings['GRAFANA_ADMIN_PASSWORD']).encode()).decode()
    stopped = False

    def get(url, auth=None):
        request = urllib.request.Request(url, headers={'Authorization': auth} if auth else {})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    def query(expression):
        result = get('http://127.0.0.1:19090/api/v1/query?' + urllib.parse.urlencode({'query': expression}))
        assert result['status'] == 'success', 'Prometheus query failed'
        return result['data']['result']

    def wait(check, label, timeout=100):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if check():
                return
            time.sleep(2)
        raise AssertionError('Timed out: ' + label)

    def compose(*parts):
        result = subprocess.run(COMPOSE + list(parts), cwd=ROOT, capture_output=True)
        assert result.returncode == 0, 'Compose failed: ' + parts[0]

    def sample(role, path='/actuator/prometheus', key=False, jwt=False):
        # The secret is expanded inside the container, never passed in the host command line.
        command = 'curl --path-as-is -sS -w "\\n%{http_code}" '
        if key:
            command += '-H "X-Metrics-Key:$CINEMA_METRICS_KEY" '
        if jwt:
            command += '-H "Authorization:Bearer ordinary-test-token" '
        command += 'http://localhost:8080' + path
        result = subprocess.run(['docker', 'compose', 'exec', '-T', role, 'sh', '-c', command],
                                cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
        assert result.returncode == 0, 'Metrics probe failed: ' + role
        body, status = result.stdout.rsplit('\n', 1)
        return int(status), body

    def restore():
        nonlocal stopped
        if stopped:
            compose('start', '--wait', '--wait-timeout', '240', 'trade')
            stopped = False

    if args.default_off_role:
        role = args.default_off_role
        for path in ('/actuator/prometheus', '/actuator/prometheus/'):
            # Pass the preserved correct key over stdin, never on the process command line.
            result = subprocess.run(['docker', 'compose', 'exec', '-T', role, 'curl', '-sS',
                                     '--config', '-', '-o', '/dev/null', '-w', '%{http_code}',
                                     'http://localhost:8080' + path],
                                    input='header = "X-Metrics-Key: ' + secret + '"\n',
                                    cwd=ROOT, capture_output=True, text=True)
            assert result.returncode == 0 and result.stdout == '404', 'Default-off endpoint exposed'
        a.passed(role + ' default-off deployment returns 404 even with the preserved correct machine credential')
        a.report['completed'] = True
        (args.output / 'report.json').write_text(json.dumps(a.report, ensure_ascii=False, indent=2), encoding='utf-8')
        return

    try:
        for role in ROLES:
            for path in ('/actuator/prometheus', '/actuator/prometheus/', '/actuator/%70rometheus'):
                assert sample(role, path)[0] in (400, 401), 'Keyless metrics accessible: ' + role
            assert sample(role, jwt=True)[0] == 401, 'User JWT bypassed machine credential'
            status, body = sample(role, key=True)
            assert status == 200 and 'jvm_memory_used_bytes' in body, 'Private metrics unavailable: ' + role
            assert secret not in body and settings['GRAFANA_ADMIN_PASSWORD'] not in body, 'Secret in metrics'
        a.passed('seven real applications require the independent machine credential; canonical, encoded and trailing paths stay protected')
        wait(lambda: len(query('up{job="cinema"} == 1')) == 7, 'seven healthy targets')
        config = get('http://127.0.0.1:19090/api/v1/status/config')['data']['yaml']
        assert secret not in config and '/run/secrets/cinema-metrics-key' in config, 'Secret exposed in sampling config'
        a.passed('Prometheus scrapes all seven applications; sampling config contains only the credential file path')
        for _ in range(4):
            a.http('/movie/topSales?limit=5')
            time.sleep(1)
        wait(lambda: bool(query('sum(http_server_requests_seconds_count{job="cinema",service="catalog",uri!~"/actuator.*"}) > 0')), 'business HTTP meter')
        assert len(query('jvm_memory_used_bytes{job="cinema",area="heap"}')) >= 7
        assert len(query('hikaricp_connections_active{job="cinema"}')) >= 6
        a.passed('actual read requests produce HTTP meters alongside JVM and six application database pools')
        dashboard = get('http://127.0.0.1:13000/api/dashboards/uid/cinema-local-metrics', grafana_auth)['dashboard']
        assert len(dashboard['panels']) == 8
        health = get('http://127.0.0.1:13000/api/datasources/uid/cinema-prometheus/health', grafana_auth)
        assert health.get('status') == 'OK', 'Grafana Prometheus plugin is not ready'
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel='chrome', headless=True)
            page = browser.new_page(viewport={'width': 1440, 'height': 1100}, locale='zh-CN')
            page.on('pageerror', lambda error: a.report['page_errors'].append(str(error)))
            page.goto('http://127.0.0.1:13000/login', wait_until='domcontentloaded')
            page.locator('input[name="user"]').fill('cinema')
            page.locator('input[name="password"]').fill(settings['GRAFANA_ADMIN_PASSWORD'])
            page.get_by_role('button', name='Log in', exact=True).click()
            page.wait_for_url(lambda url: '/login' not in url, timeout=30000)
            a.report['console_errors'] = []
            page.on('console', lambda message: a.report['console_errors'].append(message.text)
                    if message.type == 'error' else None)
            page.goto('http://127.0.0.1:13000/d/cinema-local-metrics', wait_until='domcontentloaded')
            expect(page.get_by_text('可采集应用', exact=True)).to_be_visible(timeout=30000)
            expect(page.get_by_text('7', exact=True).first).to_be_visible(timeout=30000)
            page.wait_for_timeout(5000)
            page.screenshot(path=str(args.output / 'grafana-dashboard.png'), full_page=True)
            assert not re.search(r'Could not load plugin|plugin failed', page.locator('body').inner_text(), re.I), 'Dashboard plugin load failure'
            assert not a.report['console_errors'], 'Dashboard console errors'
            assert not a.report['page_errors'], 'Dashboard script errors'
            browser.close()
        a.passed('Grafana datasource health succeeds; real Chrome renders seven healthy targets on the eight-panel dashboard without console or script errors')
        assert a.sql('SELECT COUNT(*) FROM cinema_seata.global_table;') == '0', 'Global transaction still pending'
        assert a.sql('SELECT COUNT(*) FROM cinema_trade.undo_log;') == '0', 'Trade branch still pending'
        stopped = True
        compose('stop', 'trade')
        wait(lambda: bool(query('up{job="cinema",service="trade"} == 0')), 'Trade target down')
        assert len(query('up{job="cinema"} == 1')) == 6, 'Other targets affected'
        restore()
        wait(lambda: len(query('up{job="cinema"} == 1')) == 7, 'Trade target restored', 180)
        a.passed('stopping Trade changes only its target to down; the same container recovers to seven healthy targets')
        compose('stop', 'grafana', 'prometheus')
        try:
            a.http('/movie/topSales?limit=5')
            a.passed('business reads remain available while both optional observers are stopped')
        finally:
            compose('start', '--wait', '--wait-timeout', '120', 'prometheus', 'grafana')
        wait(lambda: len(query('up{job="cinema"} == 1')) == 7, 'observers restored')
        a.report['completed'] = True
    finally:
        restore()
        (args.output / 'report.json').write_text(json.dumps(a.report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Metrics acceptance completed=' + str(a.report['completed']))


if __name__ == '__main__':
    main()
