"""Verify real metrics/log panels and click their actual SkyWalking derived link in Chrome."""
import argparse
import json
from pathlib import Path
import urllib.parse
from ai_local_acceptance import ROOT
from playwright.sync_api import sync_playwright, expect, TimeoutError as BrowserTimeout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, default=ROOT / '.work/logs-final-20261025/report.json')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/logs-browser-acceptance')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = json.loads(args.report.read_text(encoding='utf-8'))
    assert evidence.get('completed'), 'Finish real backend log acceptance before browser checks'
    no_agent = evidence.get('without_agent', False)
    identifier = evidence['request_id'] if no_agent else evidence['trace_id']
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    report = {'completed': False, 'console_errors': [], 'page_errors': [], 'without_agent': no_agent}

    def watch(page):
        page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
        page.on('console', lambda message: report['console_errors'].append(message.text) if message.type == 'error' else None)

    def settle(page):
        # Dashboards poll continuously; bound the idle observation, then require
        # the actual authenticated state or panel data below rather than sleeping.
        page.wait_for_load_state('domcontentloaded')
        try:
            page.wait_for_load_state('networkidle', timeout=5000)
        except BrowserTimeout:
            report['continuous_polling_views'] = report.get('continuous_polling_views', 0) + 1

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel='chrome', headless=True)
            context = browser.new_context(viewport={'width': 1500, 'height': 1100}, locale='zh-CN', timezone_id='Asia/Shanghai')
            try:
                if not no_agent:
                    horizon = context.new_page()
                    horizon.goto('http://127.0.0.1:18080')
                    settle(horizon)
                    horizon.locator('input[name="username"]').fill('cinema')
                    horizon.locator('input[type="password"]').fill(settings['SKYWALKING_UI_PASSWORD'])
                    horizon.get_by_role('button', name='Sign in', exact=False).click()
                    horizon.wait_for_url(lambda url: 'login' not in url, timeout=30000)
                    settle(horizon)
                    expect(horizon.locator('input[name="username"]')).to_have_count(0)
                    assert context.request.get('http://127.0.0.1:18080/api/auth/me').json().get('username') == 'cinema'
                page = context.new_page()
                page.goto('http://127.0.0.1:13000/login')
                settle(page)
                page.locator('input[name="user"]').fill('cinema')
                page.locator('input[name="password"]').fill(settings['GRAFANA_ADMIN_PASSWORD'])
                page.get_by_role('button', name='Log in', exact=True).click()
                page.wait_for_url(lambda url: '/login' not in url, timeout=30000)
                watch(page)
                page.goto('http://127.0.0.1:13000/d/cinema-local-metrics')
                settle(page)
                expect(page.get_by_text('可采集应用', exact=True)).to_be_visible(timeout=30000)
                targets = '7' if no_agent else '5'
                expect(page.get_by_text(targets, exact=True).first).to_be_visible(timeout=30000)
                page.screenshot(path=str(args.output / 'metrics.png'), full_page=True)
                report['healthy_metric_targets'] = int(targets)
                url = 'http://127.0.0.1:13000/d/cinema-local-logs?' + urllib.parse.urlencode({'var-trace_id': identifier, 'from': 'now-1h'})
                page.goto(url)
                settle(page)
                expect(page.get_by_text('业务日志（可按 trace ID 或请求号筛选）', exact=True)).to_be_visible(timeout=30000)
                expect(page.locator('body')).to_contain_text('HTTP request completed', timeout=30000)
                expect(page.locator('body')).to_contain_text(identifier)
                for service in ('cinema-gateway', 'cinema-catalog', 'cinema-trade'):
                    expect(page.locator('body')).to_contain_text(service)
                page.screenshot(path=str(args.output / 'linked-logs.png'), full_page=True)
                page.get_by_text('HTTP request completed', exact=False).first.click()
                expect(page.get_by_role('button', name='Close details for this log', exact=True)).to_be_visible()
                links = page.locator('a[href*="127.0.0.1:18080/layer/general/trace"]')
                if no_agent:
                    expect(links).to_have_count(0)
                    report['trace_link_absent_without_agent'] = True
                else:
                    expect(links.first).to_be_visible(timeout=15000)
                    destination = links.first.get_attribute('href')
                    assert urllib.parse.parse_qs(urllib.parse.urlsplit(destination).query)['traceId'] == [identifier]
                    with context.expect_page(timeout=30000) as opened:
                        links.first.click()
                    trace = opened.value
                    watch(trace)
                    settle(trace)
                    for service in ('cinema-gateway', 'cinema-catalog', 'cinema-trade'):
                        expect(trace.get_by_text(service, exact=True).first).to_be_visible(timeout=30000)
                    assert urllib.parse.parse_qs(urllib.parse.urlsplit(trace.url).query).get('traceId') == [identifier]
                    trace.screenshot(path=str(args.output / 'clicked-skywalking-trace.png'), full_page=True)
                    report['clicked_trace_url'] = trace.url
                page.screenshot(path=str(args.output / 'log-details.png'), full_page=True)
                assert not report['console_errors'] and not report['page_errors'], 'Observability browser errors'
                report['completed'] = True
            finally:
                context.close()
                browser.close()
    finally:
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS real Chrome metrics, filtered logs and ' + ('empty trace context without Agent' if no_agent else 'clicked three-service SkyWalking trace'))


if __name__ == '__main__':
    main()
