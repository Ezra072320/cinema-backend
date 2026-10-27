"""Exercise real XXL-JOB callbacks, Redisson exclusion and local scheduling fallback.

Uses private local scheduler credentials, isolated ticket fixtures and temporary manual probe jobs.
Restores Redis, scheduler mode, job status and stopped project roles in finally.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.cookiejar
import json
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
from ai_local_acceptance import Acceptance, ROOT

BASE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
        '-f', 'deploy/local/compose.transactions.yml', '--profile', 'messaging', '--profile', 'transactions']
JOBS = BASE + ['-f', 'deploy/local/compose.jobs.yml', '--profile', 'jobs']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--admin-url', default='http://127.0.0.1:11880/xxl-job-admin')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/jobs-acceptance')
    args = parser.parse_args()
    assert urllib.parse.urlsplit(args.admin_url).hostname in ('localhost', '127.0.0.1')
    a = Acceptance(args.base_url, args.output)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    probes = []
    counters = []
    stopped = set()
    local_mode = False
    original_status = None
    main_job = None

    def wait(check, label, timeout=100):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            result = check()
            if result:
                return result
            time.sleep(1)
        raise AssertionError('Timed out: ' + label)

    def compose(*args, jobs=True, stdin=None):
        result = subprocess.run((JOBS if jobs else BASE) + list(args), input=stdin, capture_output=True,
                                text=True, encoding='utf-8', cwd=ROOT)
        assert result.returncode == 0, 'Local Compose action failed: ' + args[0]
        return result.stdout

    def admin(path, data):
        req = urllib.request.Request(args.admin_url + path, data=urllib.parse.urlencode(data).encode(), method='POST')
        with client.open(req, timeout=20) as response:
            result = json.load(response)
        assert result.get('code') == 200, 'Scheduler rejected ' + path + ': ' + str(result.get('msg'))
        return result.get('content')

    def redis(*args):
        return compose('exec', '-T', 'redis', 'sh', '-c', 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"',
                       'redis-cli', *args).strip()

    def ticket():
        return int(a.sql(f'''SELECT t.id FROM cinema_trade.ticket t JOIN cinema_trade.seat s ON s.id=t.seat_id
            WHERE t.schedule_id={int(a.schedule)} AND t.status=1 AND s.type=1 ORDER BY t.id LIMIT 1'''))

    def expired_order():
        order = a.http('/user/createOrder', {'scheduleId': a.schedule, 'ticketIds': [ticket()]})
        a.sql(f'UPDATE cinema_trade.`order` SET create_time=DATE_SUB(NOW(),INTERVAL 16 MINUTE) WHERE id={int(order)} AND user_id={int(a.uid)}')
        return int(order)

    def latest(job):
        return int(a.sql(f'SELECT COALESCE(MAX(id),0) FROM cinema_job.xxl_job_log WHERE job_id={int(job)}'))

    def trigger(job, parameter=''):
        # Registry heartbeat and the admin's routing snapshot refresh on separate schedules.
        wait(lambda: a.sql(f"SELECT address_list FROM cinema_job.xxl_job_group WHERE id={group}").strip() not in ('', 'NULL'), 'executor routing snapshot')
        before = latest(job)
        admin('/jobinfo/trigger', {'id': job, 'executorParam': parameter, 'addressList': ''})
        return int(wait(lambda: a.sql(f'SELECT id FROM cinema_job.xxl_job_log WHERE job_id={int(job)} AND id>{before} ORDER BY id DESC LIMIT 1'), 'trigger record'))

    def handled(log, expected=200):
        wait(lambda: a.sql(f'SELECT handle_code FROM cinema_job.xxl_job_log WHERE id={int(log)}') == str(expected), 'job callback ' + str(expected), timeout=80)

    def add_probe(label, parameter, timeout=30):
        name = '本地验收' + label + uuid.uuid4().hex[:8]
        admin('/jobinfo/add', {'jobGroup': group, 'jobDesc': name, 'author': 'cinema-local-acceptance',
            'alarmEmail': '', 'scheduleType': 'NONE', 'scheduleConf': '', 'misfireStrategy': 'DO_NOTHING',
            'executorRouteStrategy': 'FIRST', 'executorHandler': 'jobMutexProbe', 'executorParam': parameter,
            'executorBlockStrategy': 'SERIAL_EXECUTION', 'executorTimeout': timeout, 'executorFailRetryCount': 0,
            'glueType': 'BEAN', 'glueRemark': '本地独立互斥验收', 'childJobid': ''})
        job = int(a.sql(f"SELECT id FROM cinema_job.xxl_job_info WHERE job_desc='{name}' AND author='cinema-local-acceptance'"))
        probes.append(job)
        return job

    try:
        admin('/auth/doLogin', {'userName': 'admin', 'password': settings['XXL_JOB_ADMIN_PASSWORD']})
        group = int(a.sql("SELECT id FROM cinema_job.xxl_job_group WHERE app_name='cinema-trade'"))
        main_job = int(a.sql(f"SELECT id FROM cinema_job.xxl_job_info WHERE job_group={group} AND executor_handler='orderExpiryReconcile'"))
        original_status = int(a.sql(f'SELECT trigger_status FROM cinema_job.xxl_job_info WHERE id={main_job}'))
        admin('/jobinfo/stop', {'id': main_job})
        wait(lambda: a.sql(f'SELECT COUNT(*) FROM cinema_job.xxl_job_log WHERE job_id={main_job} AND trigger_code=200 AND handle_code=0') == '0', 'previous scheduled execution finishes')
        wait(lambda: a.sql("SELECT COUNT(*) FROM cinema_job.xxl_job_registry WHERE registry_key='cinema-trade' AND update_time>DATE_SUB(NOW(),INTERVAL 60 SECOND)") != '0', 'executor registration')
        a.passed('real scheduler login, persistent task definition and authenticated Trade executor registration')

        a.setup()
        order = expired_order()
        # No detail reads: the order detail endpoint itself performs lazy expiry cleanup.
        time.sleep(68)
        assert a.sql(f'SELECT status FROM cinema_trade.`order` WHERE id={order}') == '1', 'Local scheduler duplicated XXL mode'
        log = trigger(main_job)
        handled(log)
        assert a.sql(f'SELECT status FROM cinema_trade.`order` WHERE id={order}') == '4'
        assert a.sql(f'SELECT COUNT(*) FROM cinema_trade.ticket WHERE order_id={order}') == '0'
        a.passed('XXL mode has no duplicate local timer; manual trigger closes the real expired order and releases inventory')

        ident = uuid.uuid4().hex[:16]
        counters.append(ident)
        parameter = json.dumps({'probeId': ident, 'delayMs': 4000, 'fail': False})
        first = add_probe('互斥A', parameter)
        second = add_probe('互斥B', parameter)
        with ThreadPoolExecutor(max_workers=2) as pool:
            # The same authenticated cookie jar is read-only after login; no credentials are logged.
            futures = [pool.submit(trigger, j, parameter) for j in (first, second)]
            logs = [f.result() for f in futures]
        for item in logs:
            handled(item)
        assert redis('GET', 'cinema:job:probe:' + ident + ':runs') == '1'
        messages = [a.sql(f'SELECT handle_msg FROM cinema_job.xxl_job_log WHERE id={int(item)}') for item in logs]
        assert any('SKIPPED_ALREADY_RUNNING' in m for m in messages)
        a.passed('two separate real task ids contend for one Redisson lease and execute the shared action only once')

        failure = trigger(first, json.dumps({'probeId': ident, 'delayMs': 0, 'fail': True}))
        handled(failure, 500)
        recovered = trigger(first, json.dumps({'probeId': ident, 'delayMs': 0, 'fail': False}))
        handled(recovered)
        assert redis('GET', 'cinema:job:probe:' + ident + ':runs') == '3'
        a.passed('failed handler reports failure, releases its lease and a later manual retry succeeds')

        timed = add_probe('超时', parameter, timeout=1)
        timeout_log = trigger(timed, json.dumps({'probeId': ident, 'delayMs': 8000, 'fail': False}))
        handled(timeout_log, 502)
        wait(lambda: redis('EXISTS', 'cinema:job:mutex:cinema-trade:probe:' + ident) == '0', 'interrupted worker releases Redis lease', timeout=10)
        recovered = trigger(second, json.dumps({'probeId': ident, 'delayMs': 0, 'fail': False}))
        handled(recovered)
        assert redis('GET', 'cinema:job:probe:' + ident + ':runs') == '5'
        a.passed('XXL timeout interrupts the worker, releases its lease and permits another task to execute')

        compose('stop', 'redis')
        stopped.add('redis')
        failure = trigger(main_job)
        handled(failure, 500)
        compose('up', '-d', '--no-deps', '--wait', 'redis')
        stopped.discard('redis')
        recovered = trigger(main_job)
        handled(recovered)
        a.passed('Redis outage fails the scheduled task without executing unlocked; recovery succeeds without changing Lettuce configuration')

        # Switch only this project's Trade back to the original LOCAL mode while the scheduler is off.
        compose('stop', 'xxl-job')
        stopped.add('xxl-job')
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'trade', jobs=False)
        local_mode = True
        wait(lambda: a.sql("SELECT COUNT(*) FROM cinema_trade.service_operation") is not None, 'local application database')
        # Let gateway's instance health probe observe the replacement process before the write.
        time.sleep(7)
        fallback = expired_order()
        wait(lambda: a.sql(f'SELECT status FROM cinema_trade.`order` WHERE id={fallback}') == '4', 'local timer fallback', timeout=85)
        assert a.sql(f'SELECT COUNT(*) FROM cinema_trade.ticket WHERE order_id={fallback}') == '0'
        a.passed('disabling XXL mode restores the existing local expiry timer and ordinary ticket handling')

        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'xxl-job')
        stopped.discard('xxl-job')
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'trade')
        local_mode = False
        wait(lambda: a.sql("SELECT COUNT(*) FROM cinema_job.xxl_job_registry WHERE registry_key='cinema-trade' AND update_time>DATE_SUB(NOW(),INTERVAL 20 SECOND)") != '0', 'restored executor')
        log = trigger(main_job)
        handled(log)
        detail = admin('/joblog/logDetailCat', {'logId': log, 'fromLineNum': 1})
        assert 'closed=' in detail.get('logContent', '')
        a.passed('XXL executor reconnects after replacement and the persistent log volume still serves execution details')

        from playwright.sync_api import sync_playwright, expect
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            context = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
            # Transfer only the login cookie in memory. No cookie values enter reports/screenshots.
            context.add_cookies([{'name': c.name, 'value': c.value, 'domain': '127.0.0.1', 'path': c.path or '/'} for c in cookies])
            page = context.new_page()
            page.on('pageerror', lambda error: a.report['page_errors'].append(str(error)))
            page.goto(args.admin_url + '/joblog', wait_until='domcontentloaded')
            expect(page.locator('body')).to_contain_text('调度日志', timeout=25000)
            page.screenshot(path=str(args.output / 'scheduler-logs.png'), full_page=True)
            page.goto(args.admin_url + '/joblog/logDetailPage?id=' + str(log), wait_until='domcontentloaded')
            expect(page.locator('body')).to_contain_text('closed=', timeout=25000)
            page.screenshot(path=str(args.output / 'scheduler-execution.png'), full_page=True)
            browser.close()
        a.passed('real scheduler UI displays authenticated execution history and fetched handler output')
        a.report['completed'] = True
    finally:
        for role in list(stopped):
            compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', role)
        if local_mode:
            compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'trade')
        if main_job is not None and original_status == 1:
            admin('/jobinfo/start', {'id': main_job})
        for job in probes:
            admin('/jobinfo/remove', {'id': job})
        for ident in counters:
            redis('DEL', 'cinema:job:probe:' + ident + ':runs')
        if a.uid:
            a.sql(f"DELETE FROM cinema_trade.event_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.userId'))='{int(a.uid)}'; DELETE FROM cinema_notify.notification WHERE user_id={int(a.uid)};")
        a.cleanup()
        (args.output / 'report.json').write_text(json.dumps(a.report, ensure_ascii=False, indent=2), encoding='utf-8')
    assert not a.report['page_errors'], 'Scheduler browser script errors detected'


if __name__ == '__main__':
    main()
