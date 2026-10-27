"""Real local AT/MQ/coupon/browser acceptance; restores stopped project roles and removes own fixtures.

Only the local transactions overlay enables the authenticated internal fault experiment.
Existing volumes, business records, topics and consumer offsets are never reset.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
from ai_local_acceptance import Acceptance, ROOT

COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'deploy/local/compose.messaging.yml',
           '-f', 'deploy/local/compose.transactions.yml', '--profile', 'messaging', '--profile', 'transactions']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/benefit-acceptance')
    parser.add_argument('--skip-ai', action='store_true', help='Skip the external Qwen conversation only')
    parser.add_argument('--smoke', action='store_true', help='Only verify AT checkout, wallet, payment/refund and cancellation')
    parser.add_argument('--verify-logs', action='store_true', help='Require actual Trade/Benefit order and XID correlation in the local Loki logs group')
    args = parser.parse_args()
    a = Acceptance(args.base_url, args.output)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    templates = []
    stopped = set()
    experiment_xids = set()
    uncertain_checkout = False
    uncertain_template = False
    report = a.report
    pool = ThreadPoolExecutor(max_workers=3)

    def wait(check, label, timeout=110):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                value = check()
                if value:
                    return value
            except (urllib.error.URLError, ConnectionError):
                pass
            time.sleep(1)
        raise AssertionError('Timed out: ' + label)

    def compose(*args):
        result = subprocess.run(COMPOSE + list(args), cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
        assert result.returncode == 0, 'Local Compose action failed: ' + args[0]

    def stop(role):
        compose('stop', role)
        stopped.add(role)

    def start(role):
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '150', role)
        stopped.discard(role)

    def request(path, body=None, method=None, key=None):
        headers = {'Content-Type': 'application/json', 'Authorization': a.token}
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(a.base + '/api' + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req, timeout=40) as response:
            result = json.load(response)
        assert result.get('code') == 1, (path, result.get('msg'))
        return result.get('data')

    def lab(coupon, delay=0, fail=False):
        body = {'userId': a.uid, 'scheduleId': a.schedule, 'ticketIds': [ticket()],
                'couponId': coupon, 'operationId': str(uuid.uuid4()), 'delayMs': delay, 'fail': fail}
        result = subprocess.run(COMPOSE + ['exec', '-T', 'trade', 'sh', '-c',
            'curl -sS --max-time 45 -H "Content-Type: application/json" -H "X-Service-Key: $INTERNAL_SERVICE_KEY" --data-binary @- http://localhost:8080/internal/transaction-lab/checkouts'],
            input=json.dumps(body), capture_output=True, text=True, encoding='utf-8', cwd=ROOT, timeout=50)
        assert result.returncode == 0, 'Internal transaction experiment request failed'
        return json.loads(result.stdout)

    def ticket():
        return int(a.sql(f'''SELECT t.id FROM cinema_trade.ticket t JOIN cinema_trade.seat s ON s.id=t.seat_id
            WHERE t.schedule_id={int(a.schedule)} AND t.status=1 AND s.type=1 ORDER BY t.id LIMIT 1'''))

    def new_coupon():
        nonlocal uncertain_template
        previous = a.token
        a.token = admin
        name = '事务验收' + uuid.uuid4().hex[:12]
        # Persist the identity before sending: an HTTP timeout does not prove that
        # this non-idempotent administration write failed to reach the database.
        manifest = args.output / 'template-fixtures.json'
        names = json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else []
        names.append(name)
        manifest.write_text(json.dumps(names, ensure_ascii=False), encoding='utf-8')
        try:
            try:
                template = request('/admin/benefit/templates', {'name': name,
                    'discount': 10, 'minimum': 20, 'stock': 2, 'validDays': 7})
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                if not isinstance(error, urllib.error.HTTPError) or error.code >= 500:
                    uncertain_template = True
                    # Discover only this run's unique template; never repeat the write
                    # or turn a lost acknowledgement into a passing request assertion.
                    try:
                        template = wait(lambda: a.sql("SELECT id FROM cinema_benefit.coupon_template WHERE name=CONVERT(0x"
                            + name.encode('utf-8').hex() + " USING utf8mb4)"), 'template acknowledgement resolution', timeout=45)
                        templates.append(int(template))
                        uncertain_template = False
                    except AssertionError:
                        pass
                raise
            templates.append(template)
        finally:
            a.token = previous
        return request(f'/benefit/templates/{template}/claim', {}, 'POST')

    def status(coupon):
        return a.sql(f'SELECT status FROM cinema_benefit.user_coupon WHERE id={int(coupon)}')

    def create(coupon, key=None):
        nonlocal uncertain_checkout
        body = {'scheduleId': a.schedule, 'ticketIds': [ticket()], 'couponId': coupon}
        key = key or str(uuid.uuid4())
        try:
            order = request('/user/createOrder', body, 'POST', key)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if not isinstance(error, urllib.error.HTTPError) or error.code >= 500:
                # The server may still be committing after its HTTP boundary closes.
                # Preserve this fixture rather than racing a global branch's undo.
                uncertain_checkout = True
            raise
        wait(lambda: request(f'/order/{order}').get('transactionState') == 'CONFIRMED', 'global publication decision')
        return order, body, key

    def absent_notifications(order):
        return a.sql(f'SELECT COUNT(*) FROM cinema_notify.notification WHERE order_id={int(order)}') == '0'

    try:
        admin = a.http('/admin/login', {'name': settings.get('INITIAL_ADMIN_NAME', 'admin'),
                                       'password': settings['INITIAL_ADMIN_PASSWORD'], **a.captcha()})
        a.setup()
        (args.output / 'fixture.json').write_text(json.dumps({'userId': a.uid, 'username': a.name, 'scheduleId': a.schedule}), encoding='utf-8')
        c = new_coupon()
        order, body, key = create(c)
        assert request('/user/createOrder', body, 'POST', key) == order
        view = request(f'/order/{order}')
        assert round(float(view['originalAmount']) - float(view['discountAmount']), 2) == float(view['totalAmount'])
        assert status(c) == 'FROZEN'
        xid = a.sql(f'SELECT xid FROM cinema_trade.order_coupon WHERE order_id={order}')
        assert xid == a.sql(f'SELECT xid FROM cinema_benefit.coupon_reservation WHERE order_id={order}')
        wait(lambda: a.sql(f'SELECT COUNT(*) FROM cinema_notify.notification WHERE order_id={order}') == '1', 'confirmed notification')
        a.passed('real AT checkout spans Trade/Benefit with the same XID, discounted total and idempotent order')
        if args.verify_logs:
            def correlated_logs():
                selector = '{job="cinema",service=~"trade|benefit"} | json | xid="' + xid + '" | order_id="' + str(order) + '"'
                params = {'query': selector, 'start': str(time.time_ns() - 600 * 10**9),
                          'end': str(time.time_ns()), 'limit': 100, 'direction': 'forward'}
                with urllib.request.urlopen('http://127.0.0.1:13100/loki/api/v1/query_range?' + urllib.parse.urlencode(params), timeout=15) as response:
                    result = json.load(response)
                rows = [json.loads(line) for stream in result['data']['result'] for timestamp, line in stream['values']]
                rows = [row for row in rows if row.get('event') in ('order.audit.staged', 'coupon.reservation.staged')]
                return rows if {'cinema-trade', 'cinema-benefit'} <= {row['service'] for row in rows} else None
            linked = wait(correlated_logs, 'Trade/Benefit order and XID logs', timeout=150)
            assert all(row['xid'] == xid and row['order_id'] == order for row in linked)
            assert all(not ({'reason', 'password', 'authorization'} & set(row)) for row in linked)
            (args.output / 'transaction-logs.json').write_text(json.dumps(linked, ensure_ascii=False, indent=2), encoding='utf-8')
            report['log_correlation'] = {'order_id': order, 'xid': xid, 'services': sorted({row['service'] for row in linked})}
            a.passed('real AT order and XID locate staged Trade/Benefit logs without exporting the protected audit reason')

        # Actual browser: coupon wallet, payment discount, responsive view, and admin creation entry.
        from playwright.sync_api import sync_playwright, expect
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome', headless=True)
            context = browser.new_context(locale='zh-CN', timezone_id='Asia/Shanghai', viewport={'width': 1440, 'height': 1000})
            context.add_init_script('localStorage.setItem("cinema-auth",' + json.dumps(json.dumps({'token': a.token, 'role': 'USER', 'profile': None})) + ')')
            page = context.new_page()
            page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
            page.goto(a.base + '/me/coupons', wait_until='domcontentloaded')
            expect(page.get_by_role('heading', name='我的优惠券', exact=True)).to_be_visible()
            expect(page.get_by_text('订单占用中', exact=True)).to_be_visible(timeout=30000)
            page.screenshot(path=str(args.output / 'coupons-desktop.png'), full_page=True)
            page.set_viewport_size({'width': 390, 'height': 844})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
            assert page.locator('.coupon-page,.profile-card,.coupon,.claim-card').evaluate_all(
                '(nodes) => nodes.every(n => n.getBoundingClientRect().right <= innerWidth + 1 && n.getBoundingClientRect().left >= -1)'), 'Mobile content is clipped despite hidden body overflow'
            page.screenshot(path=str(args.output / 'coupons-mobile.png'), full_page=True)
            page.goto(a.base + '/pay/' + str(order), wait_until='domcontentloaded')
            expect(page.get_by_text('原价', exact=False)).to_be_visible(timeout=30000)
            page.screenshot(path=str(args.output / 'coupon-payment.png'), full_page=True)
            browser.close()
        a.passed('coupon wallet and discounted payment render in Chrome desktop/mobile with no script errors')

        request('/order/pay', {'orderId': order, 'channel': 'WECHAT'})
        wait(lambda: status(c) == 'USED', 'coupon consumption')
        request('/user/refund/' + str(order), {}, 'POST')
        assert status(c) == 'USED'
        assert float(request(f'/order/{order}')['refundAmount']) == float(view['totalAmount'])
        a.passed('payment consumes coupon and refund uses actual paid amount without returning a used coupon')

        c2 = new_coupon()
        cancel_order, _, _ = create(c2)
        request('/user/cancelOrder/' + str(cancel_order), {}, 'POST')
        wait(lambda: status(c2) == 'AVAILABLE', 'cancel release')
        a.passed('cancelled unpaid order releases its frozen coupon through durable order facts')
        if args.smoke:
            assert not report['page_errors'], 'Browser script errors detected'
            report['completed'] = True
            return

        # Independent expiry cleanup must survive a later coupon checkout rollback.
        expired = request('/user/createOrder', {'scheduleId': a.schedule, 'ticketIds': [ticket()]}, 'POST', str(uuid.uuid4()))
        a.sql(f'UPDATE cinema_trade.`order` SET create_time=DATE_SUB(NOW(),INTERVAL 16 MINUTE) WHERE id={expired} AND user_id={int(a.uid)}')
        # Both branches have committed phase one when the lab deliberately raises.
        before = a.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE user_id={int(a.uid)}')
        failed = lab(c2, fail=True)
        assert isinstance(failed, dict) and failed.get('code') != 1
        wait(lambda: status(c2) == 'AVAILABLE', 'AT rollback coupon')
        assert a.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE user_id={int(a.uid)}') == before
        assert a.sql(f'SELECT status FROM cinema_trade.`order` WHERE id={expired}') == '4'
        assert a.sql(f'SELECT COUNT(*) FROM cinema_trade.ticket WHERE order_id={expired}') == '0'
        assert a.sql(f"SELECT COUNT(*) FROM cinema_trade.ticket WHERE schedule_id={int(a.schedule)} AND order_id>0 AND order_id NOT IN (SELECT id FROM cinema_trade.`order`)") == '0'
        a.passed('failure after both phase-one commits rolls back order, ticket locks, coupon, reservation and held Outbox')

        # A visible phase-one transaction must never emit CREATED or allow payment.
        c3 = new_coupon()
        future = pool.submit(lab, c3, 18000, False)
        pending = int(wait(lambda: a.sql(f'SELECT order_id FROM cinema_trade.order_coupon WHERE coupon_id={int(c3)}'), 'phase-one row'))
        experiment_xids.add(a.sql(f'SELECT xid FROM cinema_trade.order_coupon WHERE order_id={pending}'))
        assert absent_notifications(pending)
        assert request(f'/order/{pending}')['transactionState'] == 'PROCESSING'
        try:
            request('/order/pay', {'orderId': pending, 'channel': 'WECHAT'})
            raise AssertionError('Undecided order was payable')
        except (urllib.error.HTTPError, AssertionError) as denied:
            if isinstance(denied, AssertionError):
                assert '处理中' in str(denied)
        future.result(timeout=45)
        wait(lambda: a.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE id={pending}') == '0', 'global timeout rollback')
        assert status(c3) == 'AVAILABLE' and absent_notifications(pending)
        a.passed('global timeout prevents payment/notification during phase one and recovers both databases')

        c4 = new_coupon()
        future = pool.submit(lab, c4, 12000, False)
        restarting = int(wait(lambda: a.sql(f'SELECT order_id FROM cinema_trade.order_coupon WHERE coupon_id={int(c4)}'), 'restart phase-one row'))
        restart_xid = a.sql(f'SELECT xid FROM cinema_trade.order_coupon WHERE order_id={restarting}')
        experiment_xids.add(restart_xid)
        assert a.sql(f"SELECT COUNT(*) FROM cinema_seata.global_table WHERE xid='{restart_xid}'") == '1'
        assert int(a.sql(f"SELECT COUNT(*) FROM cinema_seata.branch_table WHERE xid='{restart_xid}'")) >= 2
        stop('seata')
        assert absent_notifications(restarting)
        start('seata')
        future.result(timeout=45)
        wait(lambda: a.sql(f"SELECT (SELECT COUNT(*) FROM cinema_seata.global_table WHERE xid='{restart_xid}')+(SELECT COUNT(*) FROM cinema_trade.undo_log WHERE xid='{restart_xid}')+(SELECT COUNT(*) FROM cinema_benefit.undo_log WHERE xid='{restart_xid}')") == '0', 'all restarted coordinator branches finish', timeout=150)
        wait(lambda: a.sql(f"SELECT COUNT(*) FROM cinema_trade.event_outbox e JOIN cinema_trade.event_outbox_fence f ON f.event_id=e.event_id WHERE f.xid='{restart_xid}' AND e.status='HELD'") == '0', 'recovered final decision')
        remains = a.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE id={restarting}') == '1'
        if remains:
            assert status(c4) == 'FROZEN'
            request('/user/cancelOrder/' + str(restarting), {}, 'POST')
            wait(lambda: status(c4) == 'AVAILABLE', 'restart order cancellation')
        else:
            wait(lambda: status(c4) == 'AVAILABLE', 'restarted coupon rollback')
            assert absent_notifications(restarting), 'Rolled-back checkout published a notification'
        a.passed('TC restarts with persisted global/branch records and converges without publishing a rolled-back order')

        # Drop a real HTTP response, then replay exactly the same operation id/body.
        c5 = new_coupon()
        body = {'scheduleId': a.schedule, 'ticketIds': [ticket()], 'couponId': c5}
        key = str(uuid.uuid4())
        from urllib.parse import urlsplit
        target = urlsplit(a.base)
        conn = http.client.HTTPConnection(target.hostname, target.port, timeout=5)
        conn.request('POST', '/api/user/createOrder', json.dumps(body), {'Content-Type': 'application/json', 'Authorization': a.token, 'Idempotency-Key': key})
        # Wait until the server committed, then discard the response body containing the order id.
        # Closing before the proxy forwards the request would only test an aborted request.
        response = conn.getresponse()
        assert response.status == 200, 'Server did not commit the acknowledgement-loss request'
        conn.close()
        operation = f'web:{a.uid}:{key}'
        wait(lambda: a.sql(f"SELECT result_json FROM cinema_trade.service_operation WHERE operation_id='{operation}'"), 'lost response operation')
        recovered = request('/user/createOrder', body, 'POST', key)
        assert a.sql(f'SELECT COUNT(*) FROM cinema_benefit.coupon_reservation WHERE coupon_id={int(c5)}') == '1'
        wait(lambda: request(f'/order/{recovered}')['transactionState'] == 'CONFIRMED', 'lost response decision')
        a.passed('lost HTTP acknowledgement recovers the existing coupon order using the same idempotency key')

        # Broker outage cannot lose coupon release; the committed local fact remains in Outbox.
        stop('rocketmq-broker')
        request('/user/cancelOrder/' + str(recovered), {}, 'POST')
        assert status(c5) == 'FROZEN'
        start('rocketmq-broker')
        wait(lambda: status(c5) == 'AVAILABLE', 'MQ recovery release')
        a.passed('MQ outage retains coupon lifecycle facts and release catches up after broker recovery')
        if not args.skip_ai:
            c6 = new_coupon()

            def chat(question):
                req = urllib.request.Request(a.base + '/api/ai/chat', method='POST',
                    headers={'Content-Type': 'application/json', 'Authorization': a.token},
                    data=json.dumps({'chatId': a.chat, 'question': question}).encode())
                with urllib.request.urlopen(req, timeout=120) as response:
                    raw = response.read().decode()
                events = [json.loads(line[5:]) for line in raw.splitlines() if line.startswith('data:')]
                done = [e for e in events if isinstance(e, dict) and e.get('type') == 'done']
                assert done and done[-1].get('completed'), 'Qwen turn did not complete'
                return done[-1]

            chat('请查询我现在的优惠券，告诉我可用的券及使用门槛。')
            chosen = ticket()
            done = chat(f'现在帮我查询场次ID {a.schedule} 的可售票ID {chosen}，使用我明确选择的优惠券ID {c6}，生成购买这张票的确认服务单。请先查真实余座和价格，只拟单，等待我点击确认。')
            cards = [c for c in done.get('actions', []) if c.get('type') == 'CREATE_ORDER' and c.get('status') == 'PENDING']
            assert cards, 'Qwen did not produce a coupon booking slip'
            action = cards[0]
            payload = json.loads(a.sql(f"SELECT payload FROM cinema_ai.ai_action WHERE id='{action['id']}' AND user_id={int(a.uid)}"))
            assert payload.get('couponId') == c6 and payload.get('ticketIds') == [chosen]
            assert status(c6) == 'AVAILABLE' and '实付' in action['summary']
            # The real browser must display the same server-owned slip before confirmation.
            with sync_playwright() as p:
                browser = p.chromium.launch(channel='chrome', headless=True)
                context = browser.new_context(locale='zh-CN', timezone_id='Asia/Shanghai', viewport={'width': 1440, 'height': 1000})
                context.add_init_script('localStorage.setItem("cinema-auth",' + json.dumps(json.dumps({'token': a.token, 'role': 'USER', 'profile': None})) + ')')
                page = context.new_page()
                page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
                page.goto(a.base + '/ai?chat=' + a.chat, wait_until='domcontentloaded')
                card = page.locator('.action-card').filter(has_text='确认购票')
                expect(card.get_by_text('实付', exact=False)).to_be_visible(timeout=30000)
                page.screenshot(path=str(args.output / 'ai-coupon-slip.png'), full_page=True)
                with page.expect_response(lambda r: '/api/ai/action/' in r.url and r.url.endswith('/confirm')) as response:
                    card.get_by_role('button', name='确认办理', exact=True).click()
                result = response.value.json()
                assert result.get('code') == 1, 'Coupon confirmation failed'
                ai_order = int(result['data']['path'].split('/')[-1])
                browser.close()
            wait(lambda: request(f'/order/{ai_order}')['transactionState'] == 'CONFIRMED', 'AI checkout decision')
            assert status(c6) == 'FROZEN'
            request('/user/cancelOrder/' + str(ai_order), {}, 'POST')
            wait(lambda: status(c6) == 'AVAILABLE', 'AI coupon cancellation')
            a.passed('real Qwen multi-turn coupon lookup produces a browser slip and only user confirmation performs AT checkout')
        report['completed'] = True
    finally:
        for role in list(stopped):
            start(role)
        pool.shutdown(wait=True)
        if a.uid and not uncertain_checkout and not uncertain_template:
            uid = int(a.uid)
            experiment_xids.update(a.sql(f'SELECT c.xid FROM cinema_trade.order_coupon c JOIN cinema_trade.`order` o ON o.id=c.order_id WHERE o.user_id={uid} UNION SELECT xid FROM cinema_benefit.coupon_reservation WHERE user_id={uid};').splitlines())
            # Never edit a fixture while AT can still restore it. A failed recovery leaves evidence
            # and a manifest for diagnosis instead of causing dirty undo by racing compensation.
            for xid in experiment_xids:
                wait(lambda: a.sql(f"SELECT (SELECT COUNT(*) FROM cinema_seata.global_table WHERE xid='{xid}')+(SELECT COUNT(*) FROM cinema_trade.undo_log WHERE xid='{xid}')+(SELECT COUNT(*) FROM cinema_benefit.undo_log WHERE xid='{xid}')") == '0', 'all experiment branches before fixture cleanup', timeout=150)
            # Preserve consumed-event fingerprints; remove only this run's business fixtures.
            a.sql(f'''USE cinema_trade; DELETE f FROM cinema_trade.event_outbox_fence f JOIN cinema_trade.event_outbox e ON e.event_id=f.event_id WHERE JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.userId'))='{uid}';
                DELETE FROM cinema_trade.event_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.userId'))='{uid}';
                DELETE c FROM cinema_trade.order_coupon c JOIN cinema_trade.`order` o ON o.id=c.order_id WHERE o.user_id={uid};
                DELETE FROM cinema_benefit.coupon_reservation WHERE user_id={uid};
                DELETE FROM cinema_benefit.user_coupon WHERE user_id={uid};
                DELETE FROM cinema_notify.notification WHERE user_id={uid};''')
        if uncertain_checkout or uncertain_template:
            report['cleanup_deferred'] = True
            report['completed'] = False
        else:
            for template in templates:
                a.sql(f'DELETE FROM cinema_benefit.coupon_template WHERE id={int(template)}')
            a.cleanup()
            report['test_data_cleaned'] = True
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    assert not report['page_errors'], 'Browser script errors detected'


if __name__ == '__main__':
    main()
