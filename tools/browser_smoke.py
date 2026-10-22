"""Real Chrome browser checks for the locally running cinema application.

Reads INITIAL_ADMIN_PASSWORD from the private .env; never saves credentials/tokens.
Does not create accounts, halls, sessions, orders, reviews or external sync jobs.
Requires: pip install -r tools/requirements-browser.txt and installed Google Chrome.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.work/python-libs'))
from playwright.sync_api import expect, sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--cors-url', help='Gateway URL for cross-origin probes; override when using the Vite proxy')
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    parser.add_argument('--redis-service', default='redis')
    parser.add_argument('--redis-database', type=int, default=0)
    parser.add_argument('--movie-id', type=int, default=14)
    parser.add_argument('--second-movie-id', type=int, default=15)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/browser-smoke')
    args = parser.parse_args()
    assert 0 <= args.redis_database <= 15
    settings = dict(line.split('=', 1) for line in args.env_file.read_text(encoding='utf-8').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    password = settings['INITIAL_ADMIN_PASSWORD']
    secrets = [v for k, v in settings.items() if v and any(s in k for s in ('PASSWORD', 'SECRET', 'KEY', '_AK', '_SK'))]
    report = {'passed': [], 'api_errors': [], 'page_errors': []}
    args.output.mkdir(parents=True, exist_ok=True)
    base = args.base_url.rstrip('/')
    parsed = urlsplit(base)
    origin = parsed.scheme + '://' + parsed.netloc
    cors_base = args.cors_url or base.replace('://localhost:', '://127.0.0.1:')
    if cors_base == base and parsed.hostname == '127.0.0.1':
        cors_base = base.replace('://127.0.0.1:', '://localhost:')

    def safe(value):
        value = str(value)
        for secret in secrets:
            value = value.replace(secret, '<REDACTED>')
        return re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '<JWT>', value)

    def passed(label):
        report['passed'].append(label)
        print('PASS ' + label, flush=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel='chrome', headless=True)
        context = browser.new_context(locale='zh-CN', timezone_id='Asia/Shanghai',
                                      viewport={'width': 1440, 'height': 1000})
        page = context.new_page()
        injecting_ranking_failure = False

        def response(result):
            path = urlsplit(result.url).path
            if not path.startswith('/api/') or (injecting_ranking_failure and path == '/api/movie/topSales'):
                return
            entry = {'path': path, 'status': result.status}
            try:
                body = result.json()
                if isinstance(body, dict) and body.get('code') != 1:
                    entry.update(code=body.get('code'), msg=safe(body.get('msg')))
                    report['api_errors'].append(entry)
                    return
            except Exception:
                pass
            if result.status >= 400:
                report['api_errors'].append(entry)

        page.on('response', response)
        page.on('pageerror', lambda e: report['page_errors'].append(safe(e)))

        def goto(path):
            page.goto(base + path, wait_until='domcontentloaded')
            try:
                page.wait_for_load_state('networkidle', timeout=15000)
            except Exception:
                # External posters/fonts must not prevent checks on the application itself.
                pass
            expect(page.locator('body')).to_be_visible()

        try:
            for path in ['/main', '/movie', f'/movie/{args.movie_id}', '/hall', '/auth/login', '/auth/register']:
                goto(path)
            passed('public pages render without API or JavaScript errors')

            titles = []
            for movie_id in [args.movie_id, args.second_movie_id]:
                result = page.request.get(base + f'/api/movie/info/{movie_id}').json()
                assert result['code'] == 1
                titles.append(result['data']['title'])
            goto(f'/movie/{args.movie_id}')
            expect(page.get_by_role('heading', name=titles[0], exact=True)).to_be_visible()
            # Use the application's mounted router, preserving the existing component instance.
            page.evaluate("async id => await document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/movie/' + id)",
                          args.second_movie_id)
            expect(page.get_by_role('heading', name=titles[1], exact=True)).to_be_visible(timeout=15000)
            page.screenshot(path=str(args.output / 'movie-detail.png'), full_page=True)
            passed('changing only the movie route id refreshes the displayed movie')

            injecting_ranking_failure = True
            page.route('**/api/movie/topSales', lambda route: route.fulfill(
                status=503, json={'code': 3001, 'msg': 'Synthetic ranking failure'}))
            goto('/main')
            expect(page.locator('.home-layout')).to_be_visible()
            page.get_by_text('影院销售', exact=True).click()
            expect(page.get_by_text('榜单暂时加载失败', exact=False)).to_be_visible()
            page.unroute('**/api/movie/topSales')
            injecting_ranking_failure = False
            passed('ranking failure stays local to the ranking and provides retry')

            goto('/auth/register')
            page.get_by_placeholder('2~16 个字符', exact=True).fill('browser_probe')
            page.get_by_placeholder('6~16 位', exact=True).fill('SyntheticPass12345')
            page.get_by_placeholder('再次输入', exact=True).fill('SyntheticPass12345')
            page.get_by_role('button', name=re.compile(r'^注\s*册$')).click()
            expect(page.get_by_text('密码需为 6~16 个非空字符', exact=True)).to_be_visible()
            passed('registration rejects passwords outside the server contract before submission')

            goto('/auth/adminLogin')
            page.get_by_placeholder('管理员账号', exact=True).fill('admin')
            page.get_by_placeholder('密码', exact=True).fill(password)
            image = page.locator('img[src*="/captcha/image"]').first
            image.wait_for()
            page.wait_for_function("()=>document.querySelector('img[src*=\"/captcha/image\"]')?.complete")
            ticket = parse_qs(urlsplit(image.get_attribute('src')).query)['ticket'][0]
            # Ticket is generated by the browser; constrain it before passing it to the container shell.
            assert re.fullmatch(r'[A-Za-z0-9_-]{1,64}', ticket)
            answer = subprocess.check_output(['docker', 'compose', 'exec', '-T', args.redis_service, 'sh', '-c',
                'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli -n ' + str(args.redis_database) + ' --raw GET captcha:' + ticket], cwd=ROOT).decode().strip()
            assert answer, 'Captcha answer is missing from the selected Redis database'
            page.get_by_placeholder('不区分大小写', exact=True).fill(answer)
            with page.expect_response(lambda r: urlsplit(r.url).path == '/api/admin/login') as login:
                page.get_by_role('button', name=re.compile(r'^登\s*录$')).click()
            assert login.value.json()['code'] == 1, 'Administrator login failed'
            page.wait_for_url(re.compile('/manage/'))
            passed('real captcha and administrator browser login')

            for path in ['/manage/overview', '/manage/users', '/manage/admins', '/manage/movies',
                         '/manage/halls', '/manage/schedules', '/manage/orders', '/manage/revenue']:
                goto(path)
            passed('all eight administrator pages load')
            page.get_by_role('button', name='导出报表', exact=True).click()
            with page.expect_download() as downloaded:
                page.get_by_role('menuitem', name='每日明细 CSV', exact=True).click()
            downloaded.value.save_as(str(args.output / 'revenue.csv'))
            assert (args.output / 'revenue.csv').stat().st_size > 50
            passed('administrator revenue CSV export')

            for source, expected in [(origin, 200), ('https://untrusted.example', 403)]:
                result = page.request.fetch(cors_base.rstrip('/') + '/api/admin/login', method='OPTIONS', headers={
                    'Origin': source, 'Access-Control-Request-Method': 'POST',
                    'Access-Control-Request-Headers': 'authorization,content-type,idempotency-key'})
                assert result.status == expected, f'Unexpected CORS preflight status: {result.status}'
                if expected == 200:
                    assert result.headers.get('access-control-allow-origin') == source
                    assert result.headers.get('access-control-allow-credentials') == 'true'
            passed('trusted CORS preflight succeeds; untrusted origin is rejected')
            assert not report['api_errors'], 'Unexpected application API errors; see report.json'
            assert not report['page_errors'], 'Browser JavaScript errors; see report.json'
        except Exception as error:
            report['failure'] = safe(error)
            raise
        finally:
            (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            browser.close()


if __name__ == '__main__':
    main()
