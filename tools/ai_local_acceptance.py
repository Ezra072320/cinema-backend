"""Local-only, real-model/browser acceptance. Creates and removes its own test data.

Requires Docker Compose, tools/requirements-browser.txt and installed Chrome.
Runs against the local Compose stack; never prints or persists login credentials.
"""
import argparse
import json
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.work/python-libs'))


class Acceptance:
    def __init__(self, base, output):
        assert urlsplit(base).hostname in ('localhost', '127.0.0.1', '::1'), 'Local server only'
        self.base = base.rstrip('/')
        self.output = output
        self.output.mkdir(parents=True, exist_ok=True)
        self.name = 'aia_' + uuid.uuid4().hex[:9]
        self.uid = None
        self.schedule = None
        self.token = None
        self.uncertain_confirmation = False
        self.confirm_outage = False
        self.trade_stopped = False
        self.chat = 'accept-' + uuid.uuid4().hex
        self.report = {'passed': [], 'page_errors': [], 'completed': False}

    def sql(self, query):
        result = subprocess.run(['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -N -B'],
            input=query, text=True, encoding='utf-8', capture_output=True, cwd=ROOT)
        if result.returncode:
            raise RuntimeError('Local fixture SQL failed: ' + result.stderr)
        return result.stdout.strip()

    def http(self, path, body=None, method=None):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = self.token
        request = urllib.request.Request(self.base + '/api' + path, headers=headers,
            data=json.dumps(body).encode() if body is not None else None, method=method)
        financial_write = request.get_method() != 'GET' and (path in ('/user/createOrder','/order/pay')
            or path.startswith(('/user/refund/','/user/cancelOrder/'))
            or (path.startswith('/ai/action/') and path.endswith('/confirm')))
        prior_uncertain = self.uncertain_confirmation
        if financial_write:
            self.uncertain_confirmation = True
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            if financial_write and error.code < 500:
                self.uncertain_confirmation = prior_uncertain
            raise
        if financial_write and result.get('code') != 3001:
            self.uncertain_confirmation = prior_uncertain
        assert result.get('code') == 1, (path, result.get('msg'))
        return result.get('data')

    def captcha(self):
        ticket = uuid.uuid4().hex
        with urllib.request.urlopen(self.base + '/api/captcha/image?ticket=' + ticket, timeout=15) as response:
            assert response.headers.get_content_type() == 'image/png'
            response.read()
        code = subprocess.check_output(['docker', 'compose', 'exec', '-T', 'redis', 'sh', '-c',
            'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw GET captcha:' + ticket], cwd=ROOT).decode().strip()
        assert code
        return {'captchaTicket': ticket, 'captchaCode': code}

    def passed(self, label):
        self.report['passed'].append(label)
        print('PASS ' + label, flush=True)

    def setup(self):
        password = secrets.token_hex(7)
        self.http('/user/register', {'name': self.name, 'password': password, 'age': 24, **self.captcha()})
        self.uid = int(self.sql("SELECT id FROM cinema_identity.user WHERE name='" + self.name + "'"))
        self.token = self.http('/user/login', {'name': self.name, 'password': password, **self.captcha()})
        self.passed('real captcha, registration and user login')
        fixture = self.sql('''INSERT INTO cinema_trade.schedule
            (movie_id,hall_id,start_time,end_time,price,status,seat_version_id,vip_price_multiplier,couple_price_multiplier,batch_id)
            SELECT movie_id,hall_id,DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY)+INTERVAL 19 HOUR,
                DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY)+INTERVAL 22 HOUR,price,1,seat_version_id,vip_price_multiplier,couple_price_multiplier,UUID()
            FROM cinema_trade.schedule WHERE seat_version_id IS NOT NULL ORDER BY id DESC LIMIT 1;
            SELECT LAST_INSERT_ID();''')
        self.schedule = int(fixture)
        self.sql(f'''INSERT INTO cinema_trade.ticket(schedule_id,seat_id,order_id,status,price)
            SELECT {self.schedule},t.seat_id,-1,1,t.price FROM cinema_trade.ticket t
            WHERE t.schedule_id=(SELECT id FROM (SELECT id FROM cinema_trade.schedule
                WHERE id<>{self.schedule} AND seat_version_id=(SELECT seat_version_id FROM cinema_trade.schedule WHERE id={self.schedule})
                ORDER BY id DESC LIMIT 1) source);''')
        seats = self.http(f'/schedule/{self.schedule}/seats')
        self.movie = int(self.sql(f'SELECT movie_id FROM cinema_trade.schedule WHERE id={self.schedule}'))
        assert seats.get('seats'), 'Prepare a hall and publish its seat map before running acceptance'

    def cleanup(self):
        if self.token:
            try:
                self.http('/ai/stop/' + self.chat, {}, 'POST')
                self.http('/ai/session/' + self.chat, method='DELETE')
            except Exception:
                # SQL cleanup is still required after a failed browser/network run.
                pass
        if self.uid is not None:
            assert re.fullmatch(r'aia_[0-9a-f]{9}', self.name)
            uid = int(self.uid)
            self.sql(f'''DELETE FROM cinema_trade.order_action WHERE order_id IN (SELECT id FROM cinema_trade.`order` WHERE user_id={uid});
                DELETE FROM cinema_trade.score WHERE user_id={uid};
                UPDATE cinema_trade.ticket SET status=1,order_id=-1 WHERE order_id IN (SELECT id FROM cinema_trade.`order` WHERE user_id={uid});
                DELETE FROM cinema_trade.`order` WHERE user_id={uid};
                DELETE FROM cinema_ai.ai_action WHERE user_id={uid};
                DELETE FROM cinema_ai.ai_message WHERE user_id={uid};
                DELETE FROM cinema_ai.ai_session WHERE user_id={uid};
                DELETE FROM cinema_catalog.user_want_movie WHERE user_id={uid};''')
            for database in ['identity', 'catalog', 'trade', 'ai']:
                self.sql(f'DELETE FROM cinema_{database}.service_operation WHERE user_id={uid};')
            self.sql(f"DELETE FROM cinema_identity.user WHERE id={uid} AND name='{self.name}';")
            redis = ['docker', 'compose', 'exec', '-T', 'redis', 'sh', '-c',
                     'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"', 'redis-cli']
            prefix = f'cinema:ai:{{{uid}}}:'
            keys = subprocess.check_output(redis + ['--scan', '--pattern', prefix + '*'], cwd=ROOT).decode().splitlines()
            assert all(key.startswith(prefix) for key in keys)
            if keys:
                subprocess.run(redis + ['UNLINK', *keys], cwd=ROOT, check=True, capture_output=True)
        if self.schedule is not None:
            sid = int(self.schedule)
            self.sql(f'DELETE FROM cinema_trade.ticket WHERE schedule_id={sid}; DELETE FROM cinema_trade.schedule WHERE id={sid};')

    def restore_trade(self):
        if not self.trade_stopped:
            return
        # Start the same container to preserve whichever startup group's config is active.
        result = subprocess.run(['docker','compose','start','--wait','--wait-timeout','240','trade'],cwd=ROOT,capture_output=True)
        assert result.returncode == 0, 'Unable to restore the existing Trade container'
        self.trade_stopped = False

    def browser_checks(self):
        from playwright.sync_api import sync_playwright, expect
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel='chrome', headless=True)
            context = browser.new_context(locale='zh-CN', timezone_id='Asia/Shanghai', viewport={'width': 1440, 'height': 1080})
            context.add_init_script('localStorage.setItem("cinema-auth",' + json.dumps(json.dumps({
                'token': self.token, 'role': 'USER', 'profile': None})) + ')')
            # Chrome does not retain SSE bodies for Network.getResponseBody. Observe
            # a cloned browser stream without changing the application's reader.
            context.add_init_script('''
                window.__aiCaptures = [];
                const originalFetch = window.fetch.bind(window);
                window.fetch = async (...args) => {
                    const response = await originalFetch(...args);
                    if (String(args[0]).endsWith('/ai/chat')) {
                        const capture = {raw:null,error:null};
                        window.__aiCaptures.push(capture);
                        response.clone().text().then(raw => capture.raw=raw).catch(error => capture.error=error.name);
                    }
                    return response;
                };
            ''')
            page = context.new_page()
            page.on('pageerror', lambda error: self.report['page_errors'].append(str(error)))
            page.set_default_timeout(20000)

            def goto(path):
                page.goto(self.base + path, wait_until='domcontentloaded')
                try:
                    page.wait_for_load_state('networkidle', timeout=3000)
                except Exception:
                    pass

            def send(question):
                expect(page.locator('textarea')).to_be_enabled(timeout=25000)
                count = page.evaluate('window.__aiCaptures.length')
                page.locator('textarea').fill(question)
                with page.expect_response(lambda result: urlsplit(result.url).path == '/api/ai/chat', timeout=120000) as response:
                    page.get_by_role('button', name='发送', exact=True).click()
                result = response.value
                assert result.status == 200, 'Chat request failed with HTTP ' + str(result.status)
                page.wait_for_function('count => window.__aiCaptures[count]?.raw !== null && window.__aiCaptures[count]?.raw !== undefined', arg=count, timeout=120000)
                raw = page.evaluate('count => window.__aiCaptures[count].raw', count)
                events = [json.loads(line[5:]) for line in raw.splitlines() if line.startswith('data:')]
                self.report['last_reply'] = ''.join(event for event in events if isinstance(event, str))
                terminal = [event for event in events if isinstance(event, dict) and event.get('type') == 'done']
                assert terminal and terminal[-1]['completed'], 'Model response did not complete'
                assert not any(isinstance(event, dict) and event.get('type') == 'error' for event in events)
                expect(page.locator('textarea')).to_be_enabled(timeout=25000)
                self.chat = page.url.split('chat=')[-1].split('&')[0]
                visible = self.http('/ai/actions/' + self.chat)
                assert {a['id'] for a in visible} == {a['id'] for a in terminal[-1]['actions']}
                expect(page.locator('.action-card')).to_have_count(len(visible))
                return visible, ''.join(event for event in events if isinstance(event, str))

            def card(kind):
                return page.locator('.action-card').filter(has=page.locator('.action-type', has_text=re.compile('^' + kind + '$'))).first

            def confirm(kind, action_id=None):
                item = page.locator('[data-action-id="' + action_id + '"]') if action_id else card(kind)
                self.uncertain_confirmation = True
                with page.expect_response(lambda result: '/api/ai/action/' in result.url and result.url.endswith('/confirm')) as response:
                    item.get_by_role('button', name='确认办理', exact=True).click()
                result = response.value.json()
                if result.get('code') == 1 or (response.value.status < 500 and result.get('code') != 3001):
                    self.uncertain_confirmation = False
                assert result['code'] == 1, result.get('msg')
                expect(item.locator('.action-status')).to_have_text('已办理')
                return result['data']

            try:
                for path in ['/main', '/movie', f'/movie/{self.movie}', '/hall', f'/seats/{self.schedule}', '/me/orders/5', '/me/info']:
                    goto(path)
                    expect(page.locator('#app')).to_be_visible()
                self.passed('catalog, movie details, schedules, seats, profile and order pages render')
                goto('/ai?ask=本地客服验收')
                slips, _ = send(f'请把影片ID {self.movie} 加入我的想看清单，生成待确认服务单。')
                assert any(a['type'] == 'SET_WANT' and a['status'] == 'PENDING' for a in slips)
                assert self.movie not in self.http('/movie/wants'), 'Draft must not mutate the want list'
                slips, _ = send('继续把我的年龄改为26岁，昵称不变，生成资料修改服务单，并保留刚才的想看服务单。')
                assert {'SET_WANT', 'UPDATE_PROFILE'} <= {a['type'] for a in slips}
                assert self.http('/user/info')['age'] == 24
                confirm('想看')
                profile = confirm('资料')
                assert self.movie in self.http('/movie/wants')
                assert self.http('/user/info')['age'] == 26
                again = self.http('/ai/action/' + profile['id'] + '/confirm', {}, 'POST')
                assert again['status'] == 'DONE'
                self.passed('two consecutive requests produce distinct real slips; confirmation is required and idempotent')

                slips, _ = send(f'只查询场次ID为{self.schedule}的1排1座是否可买，先不要生成购票服务单。')
                assert not any(a['type'] == 'CREATE_ORDER' for a in slips)
                slips, _ = send('就买刚才的那个座位，帮我生成购票服务单。')
                assert any(a['type'] == 'CREATE_ORDER' and a['status'] == 'PENDING' for a in slips)
                assert self.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE user_id={self.uid}') == '0'
                if self.confirm_outage:
                    self.trade_stopped = True
                    stopped = subprocess.run(['docker','compose','stop','trade'],cwd=ROOT,capture_output=True)
                    assert stopped.returncode == 0, 'Unable to pause the project Trade container'
                    self.uncertain_confirmation = True
                    with page.expect_response(lambda result: '/api/ai/action/' in result.url and result.url.endswith('/confirm')) as unavailable:
                        card('购票').get_by_role('button',name='确认办理',exact=True).click()
                    result = unavailable.value.json()
                    assert unavailable.value.status == 503 and result.get('code') == 3001
                    assert unavailable.value.headers.get('retry-after') == '2'
                    assert '先核对原办理结果' in result.get('msg','')
                    expect(card('购票').locator('.action-status')).to_have_text('待你确认')
                    assert self.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE user_id={self.uid}') == '0'
                    self.restore_trade()
                    deadline = time.monotonic() + 45
                    while True:
                        try:
                            self.http(f'/schedule/{self.schedule}/seats')
                            break
                        except (urllib.error.URLError,AssertionError):
                            assert time.monotonic() < deadline, 'Trade query route did not recover'
                            time.sleep(1)
                booking = confirm('购票')
                order_id = int(booking['path'].rsplit('/', 1)[1])
                order = self.http('/order/' + str(order_id))
                assert order['status'] == 1 and '1-1' in order['seatLabels']
                self.passed('follow-up booking preserves the exact schedule and seat; only confirmation locks inventory')
                if self.confirm_outage:
                    repeated = self.http('/ai/action/' + booking['id'] + '/confirm',{},'POST')
                    assert repeated['path'] == booking['path'] and repeated['status'] == 'DONE'
                    assert self.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE user_id={self.uid}') == '1'
                    self.passed('real Trade outage returns retryable 503 and restoring the same slip creates exactly one order')

                card('购票').get_by_role('button', name='查看订单与支付状态').click()
                page.wait_for_url(re.compile('/pay/' + str(order_id)))
                with page.expect_response(lambda result: urlsplit(result.url).path == '/api/order/pay') as paid:
                    page.get_by_role('button', name=re.compile('立即支付')).click()
                assert paid.value.json()['code'] == 1
                page.wait_for_url(re.compile('/order/' + str(order_id)))
                assert self.http('/order/' + str(order_id))['status'] == 2
                goto('/ai?chat=' + self.chat)
                slips, reply = send('我已支付，请现查刚才订单的当前支付状态，先不要退款。')
                assert '已支付' in reply and not any(a['type'] == 'REFUND_ORDER' for a in slips)
                slips, _ = send('现在帮我退款，生成退票服务单。')
                assert any(a['type'] == 'REFUND_ORDER' and a['status'] == 'PENDING' for a in slips)
                assert self.http('/order/' + str(order_id))['status'] == 2
                confirm('退票')
                assert self.http('/order/' + str(order_id))['status'] == 3
                self.passed('browser payment, live payment-state lookup and confirmed refund complete in one conversation')

                slips, _ = send(f'请帮我买场次ID为{self.schedule}的1排2座，生成购票服务单。')
                second = next(a for a in slips if a['type'] == 'CREATE_ORDER' and a['status'] == 'PENDING')
                second_card = page.locator('[data-action-id="' + second['id'] + '"]')
                second_card.get_by_role('button', name='撤销', exact=True).click()
                expect(second_card.locator('.action-status')).to_have_text('已撤销')
                assert self.sql(f'SELECT COUNT(*) FROM cinema_trade.`order` WHERE user_id={self.uid}') == '1'
                assert self.http('/ai/actions/' + self.chat)[0]['id'] == second['id']
                self.passed('dismissing a slip leaves business records unchanged')

                slips, _ = send(f'帮我买场次ID为{self.schedule}的1排2座，生成新的购票服务单。')
                pending_booking = next(a for a in slips if a['type'] == 'CREATE_ORDER' and a['status'] == 'PENDING')
                created = confirm('购票',pending_booking['id'])
                canceled_order = int(created['path'].rsplit('/',1)[1])
                slips, _ = send(f'帮我取消订单ID为{canceled_order}的待支付订单，生成取消服务单。')
                assert any(a['type'] == 'CANCEL_ORDER' and a['status'] == 'PENDING' for a in slips)
                confirm('取消')
                assert self.http('/order/' + str(canceled_order))['status'] == 4
                self.passed('a new booking after dismissal can be confirmed and then canceled through a separate slip')

                # Create a paid order through the regular API, then move only this
                # disposable schedule into the past to exercise review eligibility.
                ticket = int(self.sql(f'SELECT id FROM cinema_trade.ticket WHERE schedule_id={self.schedule} AND status=1 ORDER BY id LIMIT 1'))
                review_order = self.http('/user/createOrder', {'scheduleId': self.schedule, 'ticketIds': [ticket]})
                self.http('/order/pay', {'orderId': review_order, 'channel': 'WECHAT'})
                self.sql(f'UPDATE cinema_trade.schedule SET start_time=NOW()-INTERVAL 3 HOUR,end_time=NOW()-INTERVAL 1 HOUR WHERE id={self.schedule}')
                comment = '本地验收：节奏不错。'
                slips, _ = send(f'为订单ID为{review_order}提交5分观影评价，评论原文是“{comment}”，请生成评价服务单。')
                assert any(a['type'] == 'SUBMIT_SCORE' and a['status'] == 'PENDING' for a in slips)
                assert self.http('/user/score/info/' + str(review_order)) is None
                confirm('评价')
                review = self.http('/user/score/info/' + str(review_order))
                assert review['score'] == 5 and review['content'] == comment
                self.passed('the sixth service type submits the exact user review only after confirmation')

                page.locator('textarea').fill('请详细介绍购票、选座、支付和退票规则。')
                page.get_by_role('button', name='发送', exact=True).click()
                with page.expect_response(lambda result: '/api/ai/stop/' in result.url) as stopped:
                    page.get_by_role('button', name='停止回复', exact=True).click()
                assert stopped.value.json()['code'] == 1
                expect(page.locator('textarea')).to_be_enabled(timeout=15000)
                _, reply = send('停止之后继续查询我的资料，告诉我现在的年龄。')
                assert '26' in reply and '### 购票' not in reply, 'A stopped question must not be resumed implicitly'
                self.passed('stop before the first token releases the server generation; the next request succeeds')

                expected_cards = len(self.http('/ai/actions/' + self.chat))
                assert int(self.sql(f"SELECT COUNT(*) FROM cinema_ai.ai_message WHERE user_id={self.uid} AND role='tool_context'")) > 0
                assert all(message['role'] in ('user', 'assistant') for message in self.http('/ai/history/' + self.chat))
                goto('/ai?chat=' + self.chat)
                expect(page.locator('.action-card')).to_have_count(expected_cards)
                assert 'tool_context' not in page.locator('.messages').inner_text()
                page.screenshot(path=str(self.output / 'ai-desktop.png'), full_page=True)
                page.set_viewport_size({'width': 390, 'height': 844})
                page.screenshot(path=str(self.output / 'ai-mobile.png'), full_page=True)
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1')
                self.passed('reload restores the selected conversation and all slips; mobile layout has no page overflow')
                assert not self.report['page_errors'], self.report['page_errors']
                self.report['completed'] = True
            except Exception as error:
                self.report['failure'] = type(error).__name__
                page.screenshot(path=str(self.output / 'failure.png'), full_page=True)
                raise
            finally:
                context.close()
                browser.close()

    def run(self):
        try:
            self.setup()
            self.browser_checks()
        finally:
            try:
                self.restore_trade()
                if self.uncertain_confirmation:
                    self.report['cleanup_deferred'] = True
                    self.report['test_data_cleaned'] = False
                    (self.output / 'fixture.json').write_text(json.dumps({'userId':self.uid,'username':self.name,
                        'scheduleId':self.schedule,'chatId':self.chat},ensure_ascii=False,indent=2),encoding='utf-8')
                else:
                    self.cleanup()
                    self.report['test_data_cleaned'] = True
                if self.report['completed']:
                    (self.output / 'failure.png').unlink(missing_ok=True)
            finally:
                (self.output / 'report.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/ai-local-acceptance')
    parser.add_argument('--confirm-outage',action='store_true',help='Pause and restore only Trade before confirming the real booking slip')
    args = parser.parse_args()
    acceptance = Acceptance(args.base_url,args.output)
    acceptance.confirm_outage = args.confirm_outage
    acceptance.run()
