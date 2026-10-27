"""Local Search/MQ/ES acceptance. Only owns its temporary fixture and this Compose project.

Retains hidden source/ES tombstones intentionally, so late deliveries cannot resurrect removed fixtures.
No volumes, consumer offsets or existing movie rows are reset. Credentials never leave private memory.
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from ai_local_acceptance import Acceptance, ROOT

sys.path.insert(0, str(ROOT / '.work/python-libs'))
from playwright.sync_api import sync_playwright, expect

COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
           '-f', 'deploy/local/compose.search.yml', '--profile', 'messaging', '--profile', 'search']


def compose(*args):
    result = subprocess.run(COMPOSE + list(args), cwd=ROOT, capture_output=True)
    if result.returncode:
        raise RuntimeError('Local Compose action failed: ' + ' '.join(args))


def wait(check, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except Exception:
            pass
        time.sleep(1)
    raise AssertionError('Timed out waiting for local search state')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/search-acceptance')
    args = parser.parse_args()
    a = Acceptance(args.base_url, args.output)
    report = a.report
    nonce = 'searchprobe' + uuid.uuid4().hex[:8]
    movie = None
    stopped = set()
    title = '星河验收' + nonce + ' <img src=x onerror=window.__searchXss=1>'

    def es(path, body=None, method=None):
        request = urllib.request.Request('http://127.0.0.1:19200' + path,
            data=json.dumps(body).encode() if body is not None else None, method=method,
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def search(**params):
        return a.http('/search/movies?' + urllib.parse.urlencode(params))

    def state():
        return a.http('/admin/search/status')

    def source_document():
        return json.loads(a.sql(f'SELECT payload FROM cinema_catalog.movie_search_document WHERE movie_id={movie}'))

    def indexed():
        return es('/cinema-movies/_doc/' + str(movie))['_source']

    def edit(summary):
        rev = int(a.sql(f'SELECT manual_revision FROM cinema_catalog.movie WHERE id={movie}'))
        a.http(f'/admin/movie/{movie}/metadata', {'fields': {'summary': summary}, 'expectedRevision': rev}, 'PUT')
        return source_document()['version']

    def synchronized(version, hidden=False):
        doc = indexed()
        return doc['version'] >= version and doc['hidden'] == hidden

    def replay(payload):
        # Isolated Outbox delivery with an old immutable snapshot, through the actual broker/consumer.
        event = uuid.uuid4().hex
        payload = json.loads(json.dumps(payload))
        payload['eventId'] = event
        encoded = json.dumps(payload, ensure_ascii=False).encode().hex()
        a.sql(f"""INSERT INTO cinema_catalog.event_outbox(event_id,topic,event_tag,payload,status,attempts,next_attempt_at,created_at)
            VALUES('{event}','cinema-movie-events','MOVIE_CHANGED',CONVERT(0x{encoded} USING utf8mb4),'PENDING',0,0,UNIX_TIMESTAMP()*1000)""")
        wait(lambda: a.sql(f"SELECT status FROM cinema_catalog.event_outbox WHERE event_id='{event}'") == 'SENT')
        time.sleep(5)

    def stop(service):
        compose('stop', service)
        stopped.add(service)

    def start(service):
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', service)
        stopped.discard(service)

    try:
        settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
                        if line and not line.startswith('#') and '=' in line)
        # Verify admin operations reject anonymous callers.
        wait(lambda: search(size=1))
        try:
            a.http('/admin/search/rebuild', {}, 'POST')
            raise AssertionError('Anonymous rebuild was accepted')
        except urllib.error.HTTPError as denied:
            assert denied.code in (401, 403)
        a.token = a.http('/admin/login', {'name': settings.get('INITIAL_ADMIN_NAME', 'admin'),
            'password': settings['INITIAL_ADMIN_PASSWORD'], **a.captcha()})
        wait(lambda: state()['status'] == 'READY')
        a.passed('public search and protected rebuild; initial versioned source scan completes')

        movie = int(a.sql(f"""INSERT INTO cinema_catalog.movie(title,summary,cat,area,score,status,runtime_min,release_date,manual_fields,manual_revision)
            VALUES('{title}','原始搜索资料','科幻','中国大陆',7.5,1,100,'2026-10-01','',0); SELECT LAST_INSERT_ID();"""))
        a.sql(f"INSERT INTO cinema_catalog.movie_alt_title(movie_id,title) VALUES({movie},'银河别名{nonce}');")
        person = int(a.sql(f"INSERT INTO cinema_catalog.person(name) VALUES('验收导演{nonce}');SELECT LAST_INSERT_ID();"))
        a.sql(f"INSERT INTO cinema_catalog.movie_crew(movie_id,person_id,role_type,sort_order) VALUES({movie},{person},2,0);")
        version = edit('第一版搜索资料')
        wait(lambda: synchronized(version))
        wait(lambda: search(q=nonce)['total'] == 1)
        original = json.loads(a.sql(f"SELECT payload FROM cinema_catalog.event_outbox WHERE JSON_EXTRACT(payload,'$.document.id')={movie} ORDER BY created_at LIMIT 1"))
        for q in ['星河验收' + nonce, '银河别名' + nonce, '验收导演' + nonce]:
            result = search(q=q)
            assert result['mode'] == 'FULLTEXT' and result['total'] == 1, (q, result['mode'], result['total'])
            assert result['rows'][0]['highlights']
        assert search(q=nonce, genre='科幻', area='中国大陆', sort='score')['total'] == 1
        assert search(q=nonce, genre='喜剧')['total'] == 0
        assert search(q=nonce, area='美国')['total'] == 0
        assert search(q=nonce, showing=True)['total'] == 0
        assert search(q=nonce, showing=False)['total'] == 1
        assert search(q=nonce, page=2, size=1)['rows'] == []
        a.passed('real Catalog transaction -> Outbox -> RocketMQ -> ES; Chinese title/alias/crew, filters and pagination')

        replay(original)
        assert indexed()['version'] == version and search(q=nonce)['total'] == 1
        a.http(f'/admin/movie/{movie}/search-visibility?hidden=true', method='PUT')
        hidden = source_document()['version']
        wait(lambda: synchronized(hidden, True))
        wait(lambda: search(q=nonce)['total'] == 0)
        replay(original)
        assert synchronized(hidden, True) and search(q=nonce)['total'] == 0
        a.passed('duplicate and out-of-order deliveries cannot overwrite a newer hidden tombstone')

        a.http(f'/admin/movie/{movie}/search-visibility?hidden=false', method='PUT')
        version = source_document()['version']
        wait(lambda: synchronized(version))
        wait(lambda: search(q=nonce)['total'] == 1)
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            context = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
            page = context.new_page()
            page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
            page.goto(a.base + '/movie?q=' + urllib.parse.quote(nonce))
            expect(page.locator('.movie-card')).to_have_count(1, timeout=30000)
            expect(page.locator('.movie-card .title mark')).to_have_count(1)
            assert page.locator('.movie-card .title img').count() == 0
            assert not page.evaluate('window.__searchXss')
            page.screenshot(path=str(a.output / 'search-desktop.png'), full_page=True)
            page.set_viewport_size({'width': 390, 'height': 844})
            assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
            page.screenshot(path=str(a.output / 'search-mobile.png'), full_page=True)
            context.close()
            browser.close()
        assert not report['page_errors']
        a.passed('real Chrome desktop/mobile search, escaped title/highlights and no script execution')

        stop('elasticsearch')
        version = edit('ES离线期间仍正常提交')
        result = wait(lambda: (r if (r := search(q=nonce, genre='科幻', area='中国大陆'))['mode'] == 'BASIC' else None))
        assert result['total'] == 1 and not result['rows'][0]['highlights']
        assert result['rows'][0]['movie']['summary'] == 'ES离线期间仍正常提交'
        readiness = subprocess.run(COMPOSE + ['exec', '-T', 'search', 'curl', '-fsS',
            'http://localhost:8080/actuator/health/readiness'], cwd=ROOT, capture_output=True, check=True)
        assert json.loads(readiness.stdout)['status'] == 'UP'
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            page = browser.new_page(locale='zh-CN')
            page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
            page.goto(a.base + '/movie?q=' + urllib.parse.quote(nonce))
            expect(page.locator('.movie-card')).to_have_count(1, timeout=30000)
            expect(page.locator('.search-note')).to_contain_text('基础检索')
            expect(page.locator('.movie-card .title mark')).to_have_count(0)
            browser.close()
        start('elasticsearch')
        wait(lambda: synchronized(version))
        wait(lambda: search(q=nonce)['mode'] == 'FULLTEXT')
        a.passed('ES outage: Catalog commits, bounded basic search, readiness stays UP; restart catches up')

        stop('rocketmq-broker')
        version = edit('MQ离线期间仍正常提交')
        assert int(a.sql(f"SELECT COUNT(*) FROM cinema_catalog.event_outbox WHERE JSON_EXTRACT(payload,'$.document.id')={movie} AND status<>'SENT'")) > 0
        start('rocketmq-broker')
        wait(lambda: synchronized(version))
        a.passed('broker outage: durable Catalog Outbox retries after restart')

        old_index = state()['active_index']
        assert a.http('/admin/search/rebuild', {}, 'POST')['started']
        assert not a.http('/admin/search/rebuild', {}, 'POST')['started']
        assert state()['building_index']
        stop('search')
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            page = browser.new_page(locale='zh-CN')
            page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
            page.goto(a.base + '/movie?q=' + urllib.parse.quote(nonce))
            expect(page.locator('.movie-card')).to_have_count(1, timeout=30000)
            expect(page.locator('.search-note')).to_contain_text('仅按片名检索')
            browser.close()
        version = edit('重建中发生的增量资料')
        start('search')
        wait(lambda: state()['status'] == 'READY' and state()['active_index'] != old_index)
        wait(lambda: synchronized(version))
        assert search(q=nonce)['rows'][0]['movie']['summary'] == '重建中发生的增量资料'
        a.passed('full rebuild resumes after process restart, captures concurrent changes and swaps alias')

        # Hidden source rows survive a further generation and delayed pre-hide events.
        a.http(f'/admin/movie/{movie}/search-visibility?hidden=true', method='PUT')
        hidden = source_document()['version']
        wait(lambda: synchronized(hidden, True))
        old_index = state()['active_index']
        a.http('/admin/search/rebuild', {}, 'POST')
        wait(lambda: state()['status'] == 'READY' and state()['active_index'] != old_index)
        replay(original)
        assert synchronized(hidden, True) and search(q=nonce)['total'] == 0
        a.passed('rebuild retains hidden tombstones; delayed old events cannot resurrect deleted search results')
        assert not report['page_errors']
        report['completed'] = True
    finally:
        for service in list(stopped):
            start(service)
        if movie:
            try:
                a.http(f'/admin/movie/{movie}/search-visibility?hidden=true', method='PUT')
                final = source_document()['version']
                wait(lambda: synchronized(final, True))
                wait(lambda: int(a.sql(f"SELECT COUNT(*) FROM cinema_catalog.event_outbox WHERE JSON_EXTRACT(payload,'$.document.id')={movie} AND status<>'SENT'")) == 0)
            finally:
                a.sql(f"""DELETE FROM cinema_catalog.movie_crew WHERE movie_id={movie};
                    DELETE FROM cinema_catalog.person WHERE name='验收导演{nonce}';
                    DELETE FROM cinema_catalog.movie_alt_title WHERE movie_id={movie};
                    DELETE FROM cinema_catalog.movie WHERE id={movie} AND title='{title}';
                    DELETE FROM cinema_catalog.event_outbox WHERE JSON_EXTRACT(payload,'$.document.id')={movie} AND status='SENT';""")
        (a.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Search acceptance completed=' + str(report['completed']), flush=True)


if __name__ == '__main__':
    main()
