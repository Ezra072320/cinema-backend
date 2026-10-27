"""Local MQ/Outbox/inbox/browser acceptance. Temporarily stops only this project's broker.

Creates isolated business fixtures and removes them. Consumed-event fingerprints and MQ history
remain for idempotency/retention; no existing topic, consumer group or volume is deleted.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from ai_local_acceptance import Acceptance, ROOT

COMPOSE=['docker','compose','-f','compose.yml','-f','deploy/local/compose.messaging.yml','--profile','messaging']

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:8088')
    parser.add_argument('--output',type=Path,default=ROOT/'target/messaging-local-acceptance')
    args=parser.parse_args()
    probe=Acceptance(args.base_url,args.output)
    broker_stopped=False

    def request(path,body=None,method=None,headers=None):
        values={'Content-Type':'application/json','Authorization':probe.token,**(headers or {})}
        req=urllib.request.Request(probe.base+'/api'+path,headers=values,
                data=json.dumps(body).encode() if body is not None else None,method=method)
        with urllib.request.urlopen(req,timeout=35) as response:
            result=json.load(response)
        assert result.get('code')==1,(path,result.get('msg'))
        return result.get('data')

    def wait_until(check,label,timeout=100):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if check(): return
            time.sleep(1)
        raise AssertionError('Timed out: '+label)

    def compose(*arguments):
        result=subprocess.run(COMPOSE+list(arguments),cwd=ROOT,capture_output=True,text=True)
        assert result.returncode==0,'Local Compose operation failed: '+str(arguments[0])

    def events_where():
        return f"JSON_UNQUOTE(JSON_EXTRACT(payload,'$.userId'))='{int(probe.uid)}'"

    def book():
        ticket=probe.sql(f'''SELECT t.id FROM cinema_trade.ticket t JOIN cinema_trade.seat s ON s.id=t.seat_id
            WHERE t.schedule_id={int(probe.schedule)} AND t.status=1 AND s.type=1 ORDER BY t.id LIMIT 1''')
        assert ticket,'No standard seat available in fixture'
        return request('/user/createOrder',{'scheduleId':probe.schedule,'ticketIds':[int(ticket)]},
                       headers={'Idempotency-Key':str(uuid.uuid4())})

    try:
        probe.setup()
        first=book()
        wait_until(lambda: request('/notify/unread-count')==1,'created notification')
        probe.passed('business commit publishes an order event and creates one authenticated notification')
        event_id=probe.sql(f"SELECT event_id FROM cinema_trade.event_outbox WHERE {events_where()} AND event_tag='CREATED' LIMIT 1")
        assert re.fullmatch('[a-f0-9-]{36}',event_id)
        probe.sql(f"UPDATE cinema_trade.event_outbox SET status='PENDING',next_attempt_at=0,sent_at=NULL WHERE event_id='{event_id}'")
        wait_until(lambda: probe.sql(f"SELECT status FROM cinema_trade.event_outbox WHERE event_id='{event_id}'")=='SENT','duplicate send')
        time.sleep(3)
        assert request('/notify/unread-count')==1
        probe.passed('replaying the same event id does not duplicate the notification')

        compose('stop','rocketmq-broker');broker_stopped=True
        request('/order/pay',{'orderId':first,'channel':'CARD'})
        assert int(probe.sql(f"SELECT COUNT(*) FROM cinema_trade.event_outbox WHERE {events_where()} AND event_tag='PAID' AND status<>'SENT'"))==1
        assert probe.sql(f'SELECT status FROM cinema_trade.`order` WHERE id={int(first)}')=='2'
        probe.passed('broker outage does not roll back payment; its event remains durable in Outbox')
        compose('start','rocketmq-broker');broker_stopped=False
        wait_until(lambda: request('/notify/unread-count')==2,'broker recovery notification',timeout=140)
        probe.passed('broker restart recovers pending delivery without a second payment')

        from playwright.sync_api import sync_playwright,expect
        with sync_playwright() as playwright:
            browser=playwright.chromium.launch(channel='chrome',headless=True)
            context=browser.new_context(locale='zh-CN',timezone_id='Asia/Shanghai',viewport={'width':1440,'height':1000})
            context.add_init_script('localStorage.setItem("cinema-auth",'+json.dumps(json.dumps({
                'token':probe.token,'role':'USER','profile':None}))+')')
            page=context.new_page()
            page.on('pageerror',lambda error: probe.report['page_errors'].append(str(error)))
            page.goto(probe.base+'/me/notifications',wait_until='domcontentloaded')
            expect(page.get_by_role('heading',name='消息通知',exact=False)).to_be_visible()
            expect(page.locator('.message-list > li')).to_have_count(2,timeout=30000)
            expect(page.get_by_text('实时更新中',exact=True)).to_be_visible(timeout=30000)
            # A native browser handshake never carries the JWT in its URL.
            page.evaluate('''async token => {
                const result=await fetch('/api/notify/socket-ticket',{method:'POST',headers:{Authorization:token}}).then(r=>r.json());
                const target=new URL('/api/notify/socket',location.origin);target.protocol='ws:';target.searchParams.set('ticket',result.data);
                const first=new WebSocket(target);window.__ticketProbe={connected:false,replayConnected:false,replayClosed:false};
                first.onmessage=e=>{if(JSON.parse(e.data).type==='notifications.connected'){window.__ticketProbe.connected=true;first.close();}};
                first.onclose=()=>{const replay=new WebSocket(target);replay.onmessage=()=>{window.__ticketProbe.replayConnected=true;replay.close();};
                    replay.onclose=()=>window.__ticketProbe.replayClosed=true;};
            }''',probe.token)
            page.wait_for_function('window.__ticketProbe?.connected && window.__ticketProbe?.replayClosed',timeout=25000)
            assert not page.evaluate('window.__ticketProbe.replayConnected')
            probe.passed('native WebSocket connects through Nginx/Gateway and one-time tickets cannot be replayed')
            request('/user/refund/'+str(first),{},'POST')
            expect(page.locator('.message-list > li')).to_have_count(3,timeout=30000)
            expect(page.get_by_text('退款已办理',exact=True)).to_be_visible()
            probe.passed('committed refund is pushed to the open notification page')
            page.get_by_role('button',name='全部已读',exact=True).click()
            wait_until(lambda: request('/notify/unread-count')==0,'read all')
            page.get_by_text('仅看未读',exact=True).click()
            expect(page.get_by_text('消息都已读完',exact=True)).to_be_visible()
            page.get_by_text('全部消息',exact=True).click()
            expect(page.locator('.message-list > li')).to_have_count(3)
            page.screenshot(path=str(args.output/'notifications-desktop.png'),full_page=True)
            page.set_viewport_size({'width':390,'height':844})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth+1')
            page.screenshot(path=str(args.output/'notifications-mobile.png'),full_page=True)
            probe.passed('read-all/unread filters work and the page fits a 390px viewport')
            browser.close()

        # Accelerate only this fixture's clock and outgoing delay, while the broker is stopped.
        compose('stop','rocketmq-broker');broker_stopped=True
        second=book()
        probe.sql(f"UPDATE cinema_trade.`order` SET create_time=DATE_SUB(NOW(),INTERVAL 16 MINUTE) WHERE id={int(second)} AND user_id={int(probe.uid)}")
        probe.sql(f"UPDATE cinema_trade.event_outbox SET delivery_timestamp=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000+4000 WHERE {events_where()} AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.orderId'))='{int(second)}' AND event_tag='PAYMENT_TIMEOUT'")
        compose('start','rocketmq-broker');broker_stopped=False
        wait_until(lambda: probe.sql(f'SELECT status FROM cinema_trade.`order` WHERE id={int(second)}')=='4','timeout close',timeout=140)
        assert probe.sql(f'SELECT COUNT(*) FROM cinema_trade.ticket WHERE order_id={int(second)} AND status=0')=='0'
        wait_until(lambda: request('/notify/unread-count')>=1,'timeout notification')
        probe.passed('delayed timeout/scan compensation closes only an expired pending fixture and releases its tickets')
        wait_until(lambda: probe.sql(f"SELECT COUNT(*) FROM cinema_trade.event_outbox WHERE {events_where()} AND status<>'SENT'")=='0','outbox drained')
        normal_count=int(probe.sql(f"SELECT COUNT(*) FROM cinema_trade.event_outbox WHERE {events_where()} AND topic='cinema-order-events'"))
        wait_until(lambda: int(probe.sql(f'SELECT COUNT(*) FROM cinema_notify.notification WHERE user_id={int(probe.uid)}'))==normal_count,'inbox drained')
        assert not probe.report['page_errors']
        probe.report['completed']=True
    finally:
        if broker_stopped: compose('start','rocketmq-broker')
        if probe.uid is not None:
            time.sleep(1)
            probe.sql(f'DELETE FROM cinema_notify.notification WHERE user_id={int(probe.uid)};')
            probe.sql(f'DELETE FROM cinema_trade.event_outbox WHERE {events_where()};')
        probe.cleanup()
        (args.output/'report.json').write_text(json.dumps(probe.report,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__': main()
