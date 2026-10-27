"""Validate persisted Sentinel rules against real Nacos, HTTP queries and both admin UIs.

Restores the original configuration and any stopped project roles. No original business data is edited.
"""
import argparse
import json
import re
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import urllib.error
from ai_local_acceptance import Acceptance, ROOT

COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
           '-f', 'deploy/local/compose.transactions.yml', '-f', 'deploy/local/compose.jobs.yml',
           '-f', 'deploy/local/compose.governance.yml', '--profile', 'messaging', '--profile', 'transactions',
           '--profile', 'jobs', '--profile', 'governance']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/governance-acceptance')
    args = parser.parse_args()
    a = Acceptance(args.base_url, args.output)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    stopped = set()
    original = None

    def wait(check, label, timeout=100):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                result = check()
                if result:
                    return result
            except (urllib.error.URLError, AssertionError):
                pass
            time.sleep(1)
        raise AssertionError('Timed out: ' + label)

    def compose(*args):
        result = subprocess.run(COMPOSE + list(args), capture_output=True, text=True, encoding='utf-8', cwd=ROOT)
        assert result.returncode == 0, 'Local Compose action failed: ' + args[0]

    def stop(role):
        compose('stop', role)
        stopped.add(role)

    def start(role):
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '210', role)
        stopped.discard(role)

    def nacos(body=None):
        token_req = urllib.request.Request('http://127.0.0.1:8848/nacos/v3/auth/user/login',
            data=urllib.parse.urlencode({'username': settings.get('NACOS_USERNAME', 'nacos'), 'password': settings['NACOS_PASSWORD']}).encode())
        with urllib.request.urlopen(token_req, timeout=10) as response:
            token = json.load(response)['accessToken']
        params = {'dataId': 'cinema-trade-seat-flow.json', 'group': 'CINEMA_GOVERNANCE', 'accessToken': token}
        base = 'http://127.0.0.1:8848/nacos/v1/cs/configs'
        request = urllib.request.Request(base + '?' + urllib.parse.urlencode(params)) if body is None else urllib.request.Request(
            base, data=urllib.parse.urlencode({**params, 'content': body, 'type': 'json'}).encode())
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.read().decode()

    def state():
        return a.http('/admin/governance/seat-rule')

    def applied(qps):
        data = state()
        return data.get('configuredQps') == qps and data.get('activeQps') == qps and data['synchronizedRule']

    def burst():
        blocked = 0
        accepted = 0
        for _ in range(12):
            request = urllib.request.Request(a.base + f'/api/schedule/{a.schedule}/seats')
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    assert json.load(response).get('code') == 1
                    accepted += 1
            except urllib.error.HTTPError as e:
                assert e.code == 429, 'Unexpected seat-query status: ' + str(e.code)
                assert e.headers.get('Retry-After') == '2'
                blocked += 1
        assert blocked > 0, 'Persistent QPS rule did not limit real queries'
        return accepted, blocked

    try:
        original = nacos()
        admin = a.http('/admin/login', {'name': settings.get('INITIAL_ADMIN_NAME', 'admin'),
                                       'password': settings['INITIAL_ADMIN_PASSWORD'], **a.captcha()})
        a.setup()
        try:
            a.http('/admin/governance/seat-rule', {'qps': 1}, 'PUT')
            raise AssertionError('User changed an administrator rule')
        except urllib.error.HTTPError as denied:
            assert denied.code == 403
        a.token = admin
        assert state()['enabled']
        a.passed('Nacos-backed governance is enabled and ordinary users cannot change its rule')

        from playwright.sync_api import sync_playwright, expect
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            context = browser.new_context(locale='zh-CN', timezone_id='Asia/Shanghai', viewport={'width': 1440, 'height': 1000})
            context.add_init_script('localStorage.setItem("cinema-auth",' + json.dumps(json.dumps({'token': admin, 'role': 'ADMIN', 'profile': None})) + ')')
            page = context.new_page()
            page.on('pageerror', lambda error: a.report['page_errors'].append(str(error)))
            page.goto(a.base + '/manage/governance', wait_until='domcontentloaded')
            expect(page.get_by_role('heading', name='选座查询流控')).to_be_visible()
            expect(page.get_by_role('spinbutton')).to_be_visible(timeout=30000)
            page.get_by_role('spinbutton').fill('2')
            page.get_by_role('button', name='保存并下发', exact=True).click()
            wait(lambda: applied(2), 'saved policy reaches Sentinel')
            assert json.loads(nacos())[0]['count'] == 2
            page.screenshot(path=str(args.output / 'governance-desktop.png'), full_page=True)
            page.set_viewport_size({'width': 390, 'height': 844})
            expect(page.get_by_role('button', name='展开侧栏', exact=True)).to_be_visible()
            expect(page.locator('.aside-backdrop')).to_have_count(0)
            expect(page.locator('.admin-aside')).not_to_be_visible()
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
            page.screenshot(path=str(args.output / 'governance-mobile.png'), full_page=True)
            browser.close()
        burst()
        a.passed('real admin UI publishes a persistent Nacos rule and public seat queries return 429 with Retry-After')

        before = nacos()
        # Existing HTTP validation returns a business failure body; it must not alter the config.
        request = urllib.request.Request(a.base + '/api/admin/governance/seat-rule', method='PUT',
            headers={'Content-Type': 'application/json', 'Authorization': admin}, data=b'{"qps":0}')
        with urllib.request.urlopen(request, timeout=10) as response:
            assert json.load(response).get('code') != 1
        assert nacos() == before
        nacos('[{"resource":"wrong-resource","count":1}]')
        time.sleep(3)
        burst()
        nacos(before)
        wait(lambda: applied(2), 'valid configuration restored')
        a.passed('invalid API input cannot publish and malformed pushed config does not erase the last working rule')

        stop('nacos')
        burst()
        start('nacos')
        wait(lambda: applied(2), 'Nacos reconnects')
        a.passed('loaded seat-query policy remains effective while Nacos is temporarily unavailable')

        compose('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '210', 'trade')
        wait(lambda: applied(2), 'recreated Trade reloads persistent policy', timeout=120)
        burst()
        a.passed('Trade replacement reloads the stored threshold and continues enforcing it')

        stop('sentinel-dashboard')
        burst()
        start('sentinel-dashboard')
        a.passed('dashboard outage does not disable the Nacos-backed business resource')

        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            page = browser.new_page(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
            page.on('pageerror', lambda error: a.report['page_errors'].append(str(error)))
            page.goto('http://127.0.0.1:18880/#/login', wait_until='domcontentloaded')
            page.locator('input[ng-model="username"]').fill('cinema')
            page.locator('input[type="password"]').fill(settings['SENTINEL_DASHBOARD_PASSWORD'])
            page.get_by_role('button', name='登录', exact=True).click()
            page.wait_for_url('**/#/dashboard*')
            # Discovery is asynchronous after dashboard restart; HTTP 200 alone
            # does not prove the heartbeat payload was accepted by Sentinel.
            wait(lambda: 'cinema-trade' in page.request.get(
                'http://127.0.0.1:18880/app/names.json').json().get('data', []),
                'dashboard accepts the real Trade heartbeat', timeout=60)
            machines = page.request.get('http://127.0.0.1:18880/app/cinema-trade/machines.json').json()['data']
            import ipaddress
            assert machines and all(ipaddress.ip_address(m['ip']) for m in machines)
            page.reload(wait_until='domcontentloaded')
            expect(page.get_by_role('link', name=re.compile(r'^cinema-trade\s')).first).to_be_visible(timeout=45000)
            page.goto('http://127.0.0.1:18880/#/dashboard/flow/cinema-trade', wait_until='domcontentloaded')
            expect(page.get_by_text('trade:seat-query', exact=True).first).to_be_visible(timeout=45000)
            page.screenshot(path=str(args.output / 'sentinel-dashboard.png'), full_page=True)
            browser.close()
        a.passed('authenticated Sentinel dashboard discovers Trade and shows the actual resource rule')
        logs = subprocess.check_output(['docker', 'exec', 'cinema-cloud-trade-1', 'sh', '-c',
            'cat /home/cinema/logs/csp/sentinel-record.log* 2>/dev/null'], cwd=ROOT).decode('utf-8', errors='replace')
        assert settings['NACOS_PASSWORD'] not in logs, 'Credential appeared in Sentinel record logs'
        a.passed('Sentinel record logs do not contain the Nacos password')
        a.report['completed'] = True
    finally:
        for role in list(stopped):
            start(role)
        if original is not None:
            nacos(original)
            restored = json.loads(original)
            wait(lambda: applied(restored[0]['count'] if restored else None), 'original rule restored')
        a.cleanup()
        (args.output / 'report.json').write_text(json.dumps(a.report, ensure_ascii=False, indent=2), encoding='utf-8')
    assert not a.report['page_errors'], 'Browser script errors detected'


if __name__ == '__main__':
    main()
