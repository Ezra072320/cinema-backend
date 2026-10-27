"""Exercise current RBAC across Identity, Catalog, Trade, Benefit and real Chrome.

Uses temporary accounts and a temporary coupon, then removes only its own data.
No existing administrator's grants or password are changed.
"""
import argparse
import json
import re
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from ai_local_acceptance import Acceptance, ROOT
from governance_local_acceptance import COMPOSE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/rbac-acceptance')
    args = parser.parse_args()
    a = Acceptance(args.base_url, args.output)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    prefix = 'ra' + uuid.uuid4().hex[:8]
    fixtures = {}
    coupon_id = None
    identity_stopped = False
    root_token = None

    def call(token, path, body=None, method=None, status=200):
        request = urllib.request.Request(a.base + '/api' + path,
            headers={'Authorization': token, 'Content-Type': 'application/json'},
            data=json.dumps(body).encode() if body is not None else None, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                assert response.status == status, 'Unexpected status: ' + path
                data = json.load(response)
                if status == 200:
                    assert data.get('code') == 1, 'Business request failed: ' + path
                return data.get('data')
        except urllib.error.HTTPError as e:
            assert e.code == status, 'Unexpected HTTP failure: ' + path + ' ' + str(e.code)
            if status == 503:
                assert e.headers.get('Retry-After') == '2'
            return None

    def compose(*args):
        result = subprocess.run(COMPOSE + list(args), cwd=ROOT, capture_output=True)
        assert result.returncode == 0, 'Local Compose operation failed: ' + args[0]

    def role(account, codes):
        return call(root_token, '/admin/rbac/admins/' + str(account['id']), {'roles': codes}, 'PUT')

    def grant_test(name, token):
        assert call(token, '/admin/access')['roles'] == [name]

    try:
        root_token = a.http('/admin/login', {'name': settings.get('INITIAL_ADMIN_NAME', 'admin'),
            'password': settings['INITIAL_ADMIN_PASSWORD'], **a.captcha()})
        root_access = call(root_token, '/admin/access')
        assert 'OWNER' in root_access['roles'] and 'admin:manage' in root_access['permissions']
        a.setup()
        call(a.token, '/admin/access', status=403)
        a.passed('existing owner keeps privileges and ordinary users cannot query administrator grants')
        for code in ('v', 'o', 'f'):
            password = secrets.token_hex(6)
            a.token = root_token
            account = a.http('/admin/addAdmin', {'name': prefix + code, 'password': password, 'age': 30})
            fixtures[code] = {'id': account['id'], 'name': prefix + code}
            token = a.http('/admin/login', {'name': prefix + code, 'password': password, **a.captcha()})
            fixtures[code]['token'] = token
            grant_test('VIEWER', token)
        a.passed('new administrators receive the persisted VIEWER default without inherited owner privileges')
        viewer = fixtures['v']
        call(viewer['token'], '/admin/schedule/list')
        call(viewer['token'], '/admin/benefit/templates')
        call(viewer['token'], '/admin/movie/sync/status')
        call(viewer['token'], '/admin/movie/0/metadata', {}, 'PUT', status=403)
        call(viewer['token'], '/admin/schedule/cancel/0', {}, 'POST', status=403)
        call(viewer['token'], '/admin/benefit/templates', {}, 'POST', status=403)
        call(viewer['token'], '/admin/list', status=403)
        call(viewer['token'], '/admin/governance/seat-rule', status=403)
        call(viewer['token'], '/statistic/revenue', status=403)
        call(viewer['token'], '/admin/rbac/admins/' + str(viewer['id']), {'roles': ['OWNER']}, 'PUT', status=403)
        grant_test('VIEWER', viewer['token'])
        a.passed('read-only account is denied writes and self-escalation across four services')
        role(fixtures['o'], ['OPERATOR'])
        operator = fixtures['o']['token']
        call(operator, '/admin/schedule/list')
        call(operator, '/admin/movie/sync/status')
        coupon_id = call(operator, '/admin/benefit/templates', {'name': prefix, 'discount': 1,
            'minimum': 10, 'stock': 1, 'validDays': 1}, 'POST')
        call(operator, '/admin/list', status=403)
        call(operator, '/admin/governance/seat-rule', status=403)
        call(operator, '/statistic/revenue', status=403)
        role(fixtures['f'], ['FINANCE'])
        finance = fixtures['f']['token']
        call(finance, '/admin/order/list')
        call(finance, '/statistic/revenue')
        call(finance, '/admin/schedule/cancel/0', {}, 'POST', status=403)
        call(finance, '/admin/list', status=403)
        a.passed('operator publishes an owned coupon while finance can read reports but cannot manage operations')
        role(fixtures['o'], ['VIEWER'])
        call(operator, '/admin/benefit/templates', {}, 'POST', status=403)
        assert 'benefit:write' not in call(operator, '/admin/access')['permissions']
        a.passed('role revocation affects the already-issued token on its next cross-service request')
        role(fixtures['o'], ['OWNER'])
        request = urllib.request.Request(a.base + '/api/admin/rbac/admins/' + str(fixtures['o']['id']),
            method='PUT', headers={'Authorization': operator, 'Content-Type': 'application/json'},
            data=json.dumps({'roles': ['VIEWER']}).encode())
        with urllib.request.urlopen(request, timeout=20) as response:
            assert json.load(response).get('code') != 1, 'Administrator could revoke its own role'
        grant_test('OWNER', operator)
        role(fixtures['o'], ['VIEWER'])
        a.passed('temporary owner cannot change its own grants and the original administrator is never demoted')

        from playwright.sync_api import sync_playwright, expect
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            def signed_page(token):
                context = browser.new_context(locale='zh-CN', timezone_id='Asia/Shanghai', viewport={'width': 1440, 'height': 1000})
                # Fabricated persisted permissions must not override authoritative access.
                context.add_init_script('localStorage.setItem("cinema-auth",' + json.dumps(json.dumps({
                    'token': token, 'role': 'ADMIN', 'profile': None, 'permissions': ['admin:manage']})) + ')')
                page = context.new_page()
                page.on('pageerror', lambda error: a.report['page_errors'].append(str(error)))
                return page
            page = signed_page(viewer['token'])
            page.goto(a.base + '/manage/benefits', wait_until='domcontentloaded')
            expect(page.get_by_role('heading', name='优惠券管理')).to_be_visible()
            expect(page.get_by_role('button', name='发布优惠券')).to_have_count(0)
            expect(page.get_by_role('menuitem', name='管理员', exact=True)).to_have_count(0)
            page.goto(a.base + '/manage/admins', wait_until='domcontentloaded')
            page.wait_for_url(re.compile(r'/403(?:\?|$)'))
            page.goto(a.base + '/manage/halls/1/seats', wait_until='domcontentloaded')
            page.wait_for_url(re.compile(r'/403(?:\?|$)'))
            page.goto(a.base + '/manage/schedules', wait_until='domcontentloaded')
            expect(page.get_by_role('heading', name='排片管理')).to_be_visible()
            expect(page.get_by_role('button', name='+ 批量排片', exact=True)).to_have_count(0)
            page.screenshot(path=str(args.output / 'viewer-desktop.png'), full_page=True)
            page.set_viewport_size({'width': 390, 'height': 844})
            expect(page.get_by_role('button', name='展开侧栏', exact=True)).to_be_visible()
            expect(page.locator('.aside-backdrop')).to_have_count(0)
            expect(page.locator('.admin-aside')).not_to_be_visible()
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
            page.screenshot(path=str(args.output / 'viewer-mobile.png'), full_page=True)
            a.passed('real read-only UI hides write controls and rejects direct routes despite forged local permissions')
            owner = signed_page(root_token)
            owner.goto(a.base + '/manage/admins', wait_until='domcontentloaded')
            search = owner.get_by_placeholder('按账号搜索')
            search.fill(viewer['name'])
            search.press('Tab')
            row = owner.get_by_role('row').filter(has_text=viewer['name'])
            expect(row).to_have_count(1)
            row.get_by_role('button', name='角色', exact=True).click()
            dialog = owner.get_by_role('dialog', name='设置管理员角色')
            dialog.get_by_text('只读人员', exact=True).click()
            expect(dialog.get_by_role('checkbox', name='只读人员', exact=True)).not_to_be_checked()
            dialog.get_by_text('财务人员', exact=True).click()
            expect(dialog.get_by_role('checkbox', name='财务人员', exact=True)).to_be_checked()
            dialog.get_by_role('button', name='保存角色', exact=True).click()
            expect(dialog).not_to_be_visible()
            grant_test('FINANCE', viewer['token'])
            call(viewer['token'], '/statistic/revenue')
            owner.screenshot(path=str(args.output / 'owner-roles.png'), full_page=True)
            browser.close()
        a.passed('owner assigns a role through the real admin dialog and the old viewer token gains only the new finance rights')
        audit_ids = ','.join(str(int(account['id'])) for account in fixtures.values())
        assert int(a.sql(f'SELECT COUNT(*) FROM cinema_identity.admin_access_audit WHERE target_id IN ({audit_ids});')) >= 6
        a.passed('real MySQL records the before and after grants for every successful role change')
        # Verify durable grants by replacing the owning service, then a real outage contract.
        compose('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '210', 'identity')
        for _ in range(40):
            try:
                grant_test('FINANCE', viewer['token'])
                break
            except (urllib.error.URLError, AssertionError):
                time.sleep(1)
        else:
            raise AssertionError('Persisted grants not restored')
        compose('stop', 'identity')
        identity_stopped = True
        call(finance, '/statistic/revenue', status=503)
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '210', 'identity')
        identity_stopped = False
        a.passed('Identity replacement restores persisted grants and unavailable verification fails closed with HTTP 503')
        assert not a.report['page_errors'], 'Browser script errors'
        a.report['completed'] = True
    finally:
        a.report['cleanup_completed'] = False
        try:
            if identity_stopped:
                compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '210', 'identity')
            if coupon_id:
                a.sql(f"DELETE FROM cinema_benefit.coupon_template WHERE id={int(coupon_id)} AND name='{prefix}' AND NOT EXISTS(SELECT 1 FROM cinema_benefit.user_coupon WHERE template_id={int(coupon_id)});")
                assert a.sql(f"SELECT COUNT(*) FROM cinema_benefit.coupon_template WHERE id={int(coupon_id)} AND name='{prefix}';") == '0', 'Fixture coupon retained because it has been claimed'
            for account in fixtures.values():
                a.sql(f"DELETE FROM cinema_identity.admin_access_audit WHERE target_id={int(account['id'])} OR actor_id={int(account['id'])}; DELETE FROM cinema_identity.admin WHERE id={int(account['id'])} AND name='{account['name']}';")
            a.cleanup()
            a.report['cleanup_completed'] = True
        except Exception:
            a.report['completed'] = False
            raise
        finally:
            (args.output / 'report.json').write_text(json.dumps(a.report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
