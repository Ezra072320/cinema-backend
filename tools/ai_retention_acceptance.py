"""Exercise actual XXL AI cleanup, Redis generation leases and MySQL rollback.

Creates one isolated user and a temporary FK blocker referencing only its own old
session. No existing conversation is backdated or deleted. Restores task state.
"""
import argparse
import http.cookiejar
import json
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid
from ai_local_acceptance import Acceptance, ROOT
from playwright.sync_api import sync_playwright, expect


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:8088')
    parser.add_argument('--admin-url',default='http://127.0.0.1:11880/xxl-job-admin')
    parser.add_argument('--output',type=Path,default=ROOT/'.work/ai-retention-acceptance')
    parser.add_argument('--check-modes',action='store_true',help='Also replace only AI to prove LOCAL timer fallback and restore XXL')
    args=parser.parse_args()
    assert urllib.parse.urlsplit(args.admin_url).hostname in ('localhost','127.0.0.1')
    a=Acceptance(args.base_url,args.output)
    settings=dict(line.split('=',1) for line in (ROOT/'.env').read_text(encoding='utf-8-sig').splitlines()
                  if line and not line.startswith('#') and '=' in line)
    cookies=http.cookiejar.CookieJar()
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    ident='rt'+uuid.uuid4().hex[:16]
    blocker='retention_probe_'+ident
    core=None
    original=None
    probe=None
    lease=None
    local_mode=False
    base_compose=['docker','compose','-f','compose.yml','-f','deploy/local/compose.messaging.yml',
                  '-f','deploy/local/compose.transactions.yml','--profile','messaging','--profile','transactions']
    remote_compose=base_compose+['-f','deploy/local/compose.jobs.yml','-f','deploy/local/compose.governance.yml','--profile','jobs','--profile','governance']

    def replace_ai(command):
        result=subprocess.run(command+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','240','ai'],cwd=ROOT,capture_output=True)
        assert result.returncode==0,'Unable to replace only the project AI application'

    def callback_listening():
        return subprocess.run(['docker','compose','exec','-T','ai','bash','-ec','exec 3<>/dev/tcp/127.0.0.1/9999'],cwd=ROOT,capture_output=True).returncode==0

    def history_ready():
        try:
            return a.http('/ai/sessions') is not None
        except (urllib.error.URLError,AssertionError):
            return False

    def admin(path,data):
        req=urllib.request.Request(args.admin_url+path,data=urllib.parse.urlencode(data).encode(),method='POST')
        with client.open(req,timeout=20) as response:
            result=json.load(response)
        assert result.get('code')==200,'Scheduler rejected '+path
        return result.get('content')

    def wait(check,label,timeout=120):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            result=check()
            if result:
                return result
            time.sleep(1)
        raise AssertionError('Timed out: '+label)

    def redis(*parts):
        result=subprocess.run(['docker','compose','exec','-T','redis','sh','-c',
            'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"','redis-cli',*parts],
            cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
        assert result.returncode==0,'Redis fixture operation failed'
        return result.stdout.strip()

    def exists(table,chat):
        return int(a.sql(f"SELECT COUNT(*) FROM cinema_ai.{table} WHERE chat_id='{chat}' AND user_id={int(a.uid)};"))

    def trigger(expected):
        # Heartbeat registration and the admin routing snapshot advance independently.
        wait(lambda:'http://ai:9999/' in a.sql(f'SELECT address_list FROM cinema_job.xxl_job_group WHERE id={group};'),'AI executor routing snapshot')
        before=int(a.sql(f'SELECT COALESCE(MAX(id),0) FROM cinema_job.xxl_job_log WHERE job_id={int(probe)};'))
        admin('/jobinfo/trigger',{'id':probe,'executorParam':'','addressList':''})
        log=int(wait(lambda:a.sql(f'SELECT id FROM cinema_job.xxl_job_log WHERE job_id={int(probe)} AND id>{before} ORDER BY id DESC LIMIT 1;'),'trigger log'))
        def outcome():
            result=a.sql(f'SELECT trigger_code,handle_code FROM cinema_job.xxl_job_log WHERE id={log};').split()
            if len(result)!=2:
                return False
            triggered,handled=map(int,result)
            if triggered>=400 or handled>0:
                detail=a.sql(f'SELECT trigger_msg,handle_msg FROM cinema_job.xxl_job_log WHERE id={log};')
                for secret in settings.values():
                    if len(secret)>=8:
                        detail=detail.replace(secret,'<REDACTED>')
                a.report.setdefault('dispatches',[]).append({'log_id':log,'expected':expected,'trigger_code':triggered,'handle_code':handled,'detail':detail})
                assert triggered==200 and handled==expected,'Unexpected task dispatch status: '+str(triggered)+'/'+str(handled)
                return True
            return False
        wait(outcome,'handler result '+str(expected))
        return log

    try:
        admin('/auth/doLogin',{'userName':'admin','password':settings['XXL_JOB_ADMIN_PASSWORD']})
        row=a.sql("SELECT i.id,i.trigger_status FROM cinema_job.xxl_job_info i JOIN cinema_job.xxl_job_group g ON g.id=i.job_group WHERE g.app_name='cinema-ai' AND i.executor_handler='aiHistoryRetention';").split()
        assert len(row)==2,'Expected one persistent AI retention task'
        core,original=map(int,row)
        if original:
            admin('/jobinfo/stop',{'id':core})
        group=int(a.sql("SELECT id FROM cinema_job.xxl_job_group WHERE app_name='cinema-ai';"))
        wait(lambda:'http://ai:9999/' in a.sql(f'SELECT address_list FROM cinema_job.xxl_job_group WHERE id={group};'),'authenticated AI executor routing')
        assert a.sql('SELECT COUNT(*) FROM cinema_ai.ai_session WHERE update_time<DATE_SUB(NOW(),INTERVAL 180 DAY);')=='0','Retain existing old histories; use a separate fixture database'
        a.passed('persistent task and authenticated AI executor are registered; existing histories are outside retention scope')
        password=secrets.token_hex(7)
        a.http('/user/register',{'name':a.name,'password':password,'age':24,**a.captcha()})
        a.uid=int(a.sql(f"SELECT id FROM cinema_identity.user WHERE name='{a.name}';"))
        a.token=a.http('/user/login',{'name':a.name,'password':password,**a.captcha()})
        chats={kind:ident+'-'+kind for kind in ('old','busy','blocked','recent')}
        for kind,chat in chats.items():
            timestamp='DATE_SUB(NOW(),INTERVAL 181 DAY)' if kind!='recent' else 'NOW()'
            a.sql(f"INSERT INTO cinema_ai.ai_session(chat_id,user_id,title,create_time,update_time) VALUES('{chat}',{a.uid},'Retention fixture',{timestamp},{timestamp}); INSERT INTO cinema_ai.ai_message(chat_id,user_id,role,content,create_time) VALUES('{chat}',{a.uid},'user','Retention fixture',{timestamp}); INSERT INTO cinema_ai.ai_action(id,user_id,chat_id,type,title,summary,payload,status,create_time,expires_at) VALUES('{ident}-{kind}',{a.uid},'{chat}','UPDATE_PROFILE','Retention fixture','fixture','{{}}','SUCCESS',{timestamp},NOW());")
        a.sql(f"INSERT INTO cinema_ai.ai_action(id,user_id,chat_id,type,title,summary,payload,status,create_time,expires_at) VALUES('{ident}-stale',{a.uid},'{chats['recent']}','UPDATE_PROFILE','Retention fixture','fixture','{{}}','PENDING',NOW(),DATE_SUB(NOW(),INTERVAL 2 HOUR)),('{ident}-future',{a.uid},'{chats['recent']}','UPDATE_PROFILE','Retention fixture','fixture','{{}}','PENDING',NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR));")
        collation=a.sql("SELECT COLLATION_NAME FROM information_schema.columns WHERE table_schema='cinema_ai' AND table_name='ai_session' AND column_name='chat_id';")
        assert re.fullmatch(r'[A-Za-z0-9_]+',collation)
        a.sql(f"CREATE TABLE cinema_ai.{blocker}(chat_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE {collation} PRIMARY KEY,FOREIGN KEY(chat_id) REFERENCES cinema_ai.ai_session(chat_id)) ENGINE=InnoDB; INSERT INTO cinema_ai.{blocker} VALUES('{chats['blocked']}');")
        lease=f'cinema:ai:{{{a.uid}}}:chat:'+chats['busy']
        assert redis('SET',lease,ident,'EX','120','NX')=='OK'
        description='本地AI清理验收'+ident
        admin('/jobinfo/add',{'jobGroup':group,'jobDesc':description,'author':'cinema-local-acceptance',
            'alarmEmail':'','scheduleType':'NONE','scheduleConf':'','misfireStrategy':'DO_NOTHING',
            'executorRouteStrategy':'FIRST','executorHandler':'aiHistoryRetention','executorParam':'',
            'executorBlockStrategy':'SERIAL_EXECUTION','executorTimeout':60,'executorFailRetryCount':0,
            'glueType':'BEAN','glueRemark':'本地隔离清理验收','childJobid':''})
        probe=int(a.sql(f"SELECT id FROM cinema_job.xxl_job_info WHERE job_desc='{description}';"))
        first=trigger(500)
        assert exists('ai_session',chats['old'])==0
        for table in ('ai_session','ai_message','ai_action'):
            assert exists(table,chats['blocked'])==1,'Per-session transaction did not roll back'
        assert exists('ai_session',chats['busy'])==1
        a.passed('real MySQL final-delete failure rolls back child deletes and XXL records failure while independent sessions commit')
        assert redis('GET',lease)==ident
        assert a.sql(f"SELECT status FROM cinema_ai.ai_action WHERE id='{ident}-stale';")=='EXPIRED'
        assert a.sql(f"SELECT status FROM cinema_ai.ai_action WHERE id='{ident}-future';")=='PENDING'
        assert a.sql(f"SELECT status FROM cinema_ai.ai_action WHERE id='{ident}-recent';")=='SUCCESS'
        a.passed('active generation lease and recent histories survive; only stale PENDING actions expire')
        a.sql(f'DROP TABLE cinema_ai.{blocker};')
        redis('EVAL',"if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",'1',lease,ident)
        second=trigger(200)
        assert exists('ai_session',chats['blocked'])==0
        assert exists('ai_session',chats['busy'])==0
        assert exists('ai_session',chats['recent'])==1
        a.passed('retry after releasing the blocker and lease finishes old sessions without deleting recent activity')
        with sync_playwright() as p:
            browser=p.chromium.launch(channel='chrome',headless=True)
            try:
                context=browser.new_context(locale='zh-CN',viewport={'width':1440,'height':1000})
                context.add_cookies([{'name':c.name,'value':c.value,'domain':urllib.parse.urlsplit(args.admin_url).hostname,'path':c.path or '/'} for c in cookies])
                page=context.new_page()
                page.on('pageerror',lambda error:a.report['page_errors'].append(str(error)))
                page.goto(args.admin_url+'/joblog/logDetailPage?id='+str(second),wait_until='domcontentloaded')
                expect(page.locator('body')).to_contain_text('purged=',timeout=30000)
                page.screenshot(path=str(args.output/'retention-execution.png'),full_page=True)
                assert not a.report['page_errors']
            finally:
                browser.close()
        a.passed('actual scheduler browser fetches AI handler counts from its execution log')
        if args.check_modes:
            fallback=ident+'-fallback'
            a.sql(f"INSERT INTO cinema_ai.ai_session(chat_id,user_id,title,create_time,update_time) VALUES('{fallback}',{a.uid},'Local timer fixture',DATE_SUB(NOW(),INTERVAL 181 DAY),DATE_SUB(NOW(),INTERVAL 181 DAY)); INSERT INTO cinema_ai.ai_message(chat_id,user_id,role,content,create_time) VALUES('{fallback}',{a.uid},'user','Local timer fixture',NOW());")
            override=ROOT/'.work/retention-local-probe.yml'
            override.write_text("services:\n  ai:\n    environment:\n      CINEMA_JOBS_AI_RETENTION_INITIAL_DELAY_MS: '2000'\n",encoding='utf-8')
            local_mode=True
            replace_ai(base_compose+['-f',str(override)])
            assert not callback_listening(),'LOCAL mode exposed an XXL callback listener'
            wait(lambda:exists('ai_session',fallback)==0,'actual local cleanup timer')
            assert exists('ai_message',fallback)==0
            wait(history_ready,'ordinary history reads after local replacement')
            a.passed('LOCAL mode has no callback listener and its real local timer cleans only the old fixture')
            replace_ai(remote_compose)
            local_mode=False
            assert callback_listening(),'XXL callback listener not restored'
            wait(lambda:a.sql("SELECT COUNT(*) FROM cinema_job.xxl_job_registry WHERE registry_key='cinema-ai' AND update_time>DATE_SUB(NOW(),INTERVAL 20 SECOND);")!='0','restored AI executor heartbeat')
            trigger(200)
            a.passed('XXL mode resumes authenticated callbacks after replacement with the same task and log volume')
        a.report['completed']=True
    finally:
        a.report['cleanup_completed']=False
        try:
            if local_mode:
                replace_ai(remote_compose)
            a.sql(f'DROP TABLE IF EXISTS cinema_ai.{blocker};')
            if probe:
                admin('/jobinfo/remove',{'id':probe})
            if core and original:
                admin('/jobinfo/start',{'id':core})
            a.cleanup()
            a.report['cleanup_completed']=True
        finally:
            (args.output/'report.json').write_text(json.dumps(a.report,ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__':
    main()
