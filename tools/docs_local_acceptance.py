"""Validate the optional local OpenAPI aggregation and actual Knife4j browser loading."""
import argparse
import json
import re
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
from ai_local_acceptance import Acceptance, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--services', default='identity,catalog,trade,ai')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/docs-acceptance')
    parser.add_argument('--disabled', action='store_true')
    args = parser.parse_args()
    assert urllib.parse.urlsplit(args.base_url).hostname in ('localhost', '127.0.0.1', '::1')
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'passed': [], 'page_errors': [], 'completed': False}
    services = args.services.split(',')
    base = args.base_url.rstrip('/')

    def passed(label):
        report['passed'].append(label)
        print('PASS ' + label, flush=True)

    def get(path):
        with urllib.request.urlopen(base + path, timeout=40) as response:
            assert response.status == 200
            return json.load(response)

    try:
        if args.disabled:
            for path in ['/doc.html', '/v3/api-docs/swagger-config', '/v3/api-docs/identity']:
                try:
                    urllib.request.urlopen(base + path, timeout=15)
                    raise AssertionError('Documentation available without the development switch')
                except urllib.error.HTTPError as error:
                    assert error.code == 404
            passed('documentation surfaces return 404 with the development switch off')
        else:
            configuration = get('/v3/api-docs/swagger-config')
            assert [url['name'] for url in configuration['urls']] == ['cinema-' + service for service in services]
            assert [url['url'] for url in configuration['urls']] == ['/v3/api-docs/' + service for service in services]
            passed('gateway aggregates exactly the selected running business domains')
            settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                            if line and not line.startswith('#') and '=' in line)
            secrets = [value for key, value in settings.items() if value and
                       any(word in key for word in ('PASSWORD', 'SECRET', '_TOKEN', '_KEY', '_AK', '_SK', 'IDENTITY_VALUE'))]
            specs = {}
            for service in services:
                spec = get('/v3/api-docs/' + service)
                assert spec['openapi'].startswith('3.')
                assert spec['info']['title'] == 'cinema-' + service + ' API'
                assert spec['paths'] and all(path.startswith('/api/') for path in spec['paths'])
                assert spec['servers'] == [{'url': '/'}]
                assert spec['components']['securitySchemes']['Authorization']['scheme'] == 'bearer'
                if service == 'identity':
                    assert spec['paths']['/api/admin/login']['post'].get('security') == []
                    assert spec['paths']['/api/admin/info']['get']['security'] == [{'Authorization': []}]
                if service == 'search':
                    assert spec['paths']['/api/search/movies']['get'].get('security') == []
                serialized = json.dumps(spec, ensure_ascii=False)
                assert all(secret not in serialized for secret in secrets), 'Secret in generated schema'
                specs[service] = len(spec['paths'])
            report['paths_per_domain'] = specs
            passed('each real schema uses same-origin API routes, JWT auth and excludes internal APIs and credential values')
            sys.path.insert(0, str(ROOT / '.work/python-libs'))
            from playwright.sync_api import sync_playwright, expect
            with sync_playwright() as p:
                browser = p.chromium.launch(channel='chrome', headless=True)
                try:
                    page = browser.new_page(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
                    page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
                    with page.expect_response(lambda response: response.url.endswith('/v3/api-docs/' + services[0]), timeout=45000) as loaded:
                        page.goto(base + '/doc.html', wait_until='domcontentloaded')
                    assert loaded.value.status == 200
                    expect(page.locator('body')).to_contain_text('cinema-' + services[0], timeout=30000)
                    page.screenshot(path=str(args.output / 'knife4j-desktop.png'), full_page=True)
                    if 'identity' == services[0]:
                        page.get_by_text('02. 管理员域', exact=True).click()
                        page.get_by_role('link', name='GET 获取当前登录管理员信息', exact=True).click()
                        page.get_by_role('tab', name='调试', exact=True).click()
                        def info_response(response):
                            return urllib.parse.urlsplit(response.url).path == '/api/admin/info'
                        targets = [field for field in page.get_by_role('textbox').all()
                                   if field.input_value() == '/api/admin/info']
                        assert len(targets) == 1, 'Unexpected debug request target'
                        assert page.request.get(base + targets[0].input_value()).status == 401
                        # After proving the UI target is same-origin, enter a live token only
                        # in this disposable context. Never screenshot or dump authorized UI.
                        a = Acceptance(base, args.output)
                        token = a.http('/admin/login', {'name': settings.get('INITIAL_ADMIN_NAME', 'admin'),
                            'password': settings['INITIAL_ADMIN_PASSWORD'], **a.captcha()})
                        page.get_by_text('Authorize', exact=True).click()
                        page.get_by_role('row').filter(has_text='Authorization').get_by_role('textbox').fill(token)
                        page.get_by_role('row').filter(has_text='Authorization').get_by_role('textbox').press('Enter')
                        page.get_by_role('row').filter(has_text='Authorization').get_by_role('textbox').press('Tab')
                        # Knife4j initializes request headers when an API tab is opened.
                        # Re-open after changing global authorization rather than reuse
                        # the already-open anonymous request editor.
                        page.goto(base + '/doc.html', wait_until='domcontentloaded')
                        page.get_by_text('02. 管理员域', exact=True).click()
                        page.get_by_role('link', name='GET 获取当前登录管理员信息', exact=True).click()
                        page.get_by_role('tab', name='调试', exact=True).click()
                        with page.expect_response(info_response) as authorized:
                            page.get_by_role('button', name=re.compile(r'^发\s*送$')).click()
                        actual_header = authorized.value.request.headers.get('authorization', '')
                        shape = 'missing' if not actual_header else ('bare' if actual_header == token else (
                            'bearer' if actual_header == 'Bearer ' + token else (
                                'lowercase-bearer' if actual_header == 'bearer ' + token else 'other')))
                        report['debug_status'] = authorized.value.status
                        report['debug_header_shape'] = shape
                        assert authorized.value.status == 200, f'Doc debug status={authorized.value.status}, header shape={shape}'
                        assert authorized.value.json().get('code') == 1
                        assert 'password' not in authorized.value.json().get('data', {})
                        assert authorized.value.request.headers.get('authorization') in (token, 'Bearer ' + token)
                        assert urllib.parse.urlsplit(authorized.value.url).netloc == urllib.parse.urlsplit(base).netloc
                        passed('private API rejects anonymous reads and real Knife4j debug succeeds with a live JWT at the same origin')
                    assert not report['page_errors'], 'Knife4j script errors'
                finally:
                    browser.close()
            passed('actual Knife4j loads its aggregated first schema in Chrome without script errors')
        report['completed'] = True
    finally:
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
