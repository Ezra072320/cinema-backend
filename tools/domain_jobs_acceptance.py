"""Local Catalog/Search XXL dispatch, real rebuild, outage and bounded source-sync checks.

Only temporary platform tasks are removed. The bounded real Catalog sync is a
normal business operation; its new movie/OSS assets are retained and counted.
Existing task preferences, indexes, broker offsets and data volumes are kept.
"""
import argparse
import http.cookiejar
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from ai_local_acceptance import Acceptance, ROOT
from playwright.sync_api import sync_playwright, expect

COMPOSE=['docker','compose','-f','compose.yml','-f','deploy/local/compose.messaging.yml',
         '-f','deploy/local/compose.jobs.yml','-f','deploy/local/compose.search.yml',
         '-f','deploy/local/compose.search-jobs.yml','--profile','messaging','--profile','jobs','--profile','search']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:8088')
    parser.add_argument('--admin-url',default='http://127.0.0.1:11880/xxl-job-admin')
    parser.add_argument('--output',type=Path,default=ROOT/'.work/domain-jobs-acceptance')
    parser.add_argument('--domain',choices=('all','catalog','search'),default='all')
    args=parser.parse_args()
    assert urllib.parse.urlsplit(args.admin_url).hostname in ('127.0.0.1','localhost')
    a=Acceptance(args.base_url,args.output)
    checked=('catalog','search') if args.domain=='all' else (args.domain,)
    a.report['domains']=list(checked)
    settings=dict(line.split('=',1) for line in (ROOT/'.env').read_text(encoding='utf-8-sig').splitlines()
                  if line and not line.startswith('#') and '=' in line)
    cookies=http.cookiejar.CookieJar()
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    probes=[]
    core=[]
    es_stopped=False
    prefix='domain'+uuid.uuid4().hex[:12]

    def wait(check,label,timeout=180):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            result=check()
            if result:
                return result
            time.sleep(1)
        raise AssertionError('Timed out: '+label)

    def admin(path,data):
        req=urllib.request.Request(args.admin_url+path,data=urllib.parse.urlencode(data).encode(),method='POST')
        with client.open(req,timeout=20) as response:
            result=json.load(response)
        assert result.get('code')==200,'Scheduler rejected '+path
        return result.get('content')

    def compose(*parts):
        result=subprocess.run(COMPOSE+list(parts),cwd=ROOT,capture_output=True)
        assert result.returncode==0,'Local Compose operation failed: '+parts[0]

    def add(app,handler,parameter='',cron=False):
        group=int(a.sql(f"SELECT id FROM cinema_job.xxl_job_group WHERE app_name='cinema-{app}';"))
        description=prefix+'-'+handler+('-cron' if cron else '-manual')+'-'+str(len(probes))
        admin('/jobinfo/add',{'jobGroup':group,'jobDesc':description,'author':'cinema-local-acceptance',
            'alarmEmail':'','scheduleType':'CRON' if cron else 'NONE','scheduleConf':'0/15 * * * * ?' if cron else '',
            'misfireStrategy':'DO_NOTHING','executorRouteStrategy':'FIRST','executorHandler':handler,
            'executorParam':parameter,'executorBlockStrategy':'SERIAL_EXECUTION','executorTimeout':930 if app=='catalog' else 330,
            'executorFailRetryCount':0,'glueType':'BEAN','glueRemark':'本地领域调度验收','childJobid':''})
        job=int(a.sql(f"SELECT id FROM cinema_job.xxl_job_info WHERE job_desc='{description}';"))
        probes.append(job)
        return job,group

    def latest(job):
        return int(a.sql(f'SELECT COALESCE(MAX(id),0) FROM cinema_job.xxl_job_log WHERE job_id={job};'))

    def handled(log,expected,timeout=180):
        def outcome():
            fields=a.sql(f'SELECT trigger_code,handle_code FROM cinema_job.xxl_job_log WHERE id={log};').split()
            if len(fields)!=2:
                return False
            triggered,completed=map(int,fields)
            if triggered>=400 or completed>0:
                a.report.setdefault('dispatches',[]).append({'log_id':log,'trigger_code':triggered,'handle_code':completed,'expected':expected})
                assert triggered==200 and completed==expected,'Unexpected dispatch result: '+str(triggered)+'/'+str(completed)
                return True
            return False
        wait(outcome,'job outcome',timeout)

    def trigger(job,group,app,expected=200,timeout=180):
        wait(lambda:'http://'+app+':9999/' in a.sql(f'SELECT address_list FROM cinema_job.xxl_job_group WHERE id={group};'),'executor routing snapshot')
        before=latest(job)
        # The admin requires this field; a blank value would overwrite the stored batch.
        parameter=a.sql(f'SELECT executor_param FROM cinema_job.xxl_job_info WHERE id={job};')
        admin('/jobinfo/trigger',{'id':job,'executorParam':parameter,'addressList':''})
        log=int(wait(lambda:a.sql(f'SELECT id FROM cinema_job.xxl_job_log WHERE job_id={job} AND id>{before} ORDER BY id LIMIT 1;'),'dispatch log'))
        handled(log,expected,timeout)
        return log

    def state():
        return a.http('/admin/search/status')

    try:
        a.token=a.http('/admin/login',{'name':settings.get('INITIAL_ADMIN_NAME','admin'),
            'password':settings['INITIAL_ADMIN_PASSWORD'],**a.captcha()})
        admin('/auth/doLogin',{'userName':'admin','password':settings['XXL_JOB_ADMIN_PASSWORD']})
        initial=a.sql("SELECT i.id,i.trigger_status,i.schedule_conf,i.executor_param FROM cinema_job.xxl_job_info i JOIN cinema_job.xxl_job_group g ON g.id=i.job_group WHERE g.app_name IN ('cinema-catalog','cinema-search') ORDER BY i.id;")
        result=subprocess.run(['python','tools/init-jobs.py'],cwd=ROOT,capture_output=True)
        assert result.returncode==0
        assert initial==a.sql("SELECT i.id,i.trigger_status,i.schedule_conf,i.executor_param FROM cinema_job.xxl_job_info i JOIN cinema_job.xxl_job_group g ON g.id=i.job_group WHERE g.app_name IN ('cinema-catalog','cinema-search') ORDER BY i.id;")
        for app in checked:
            handler='movieUpcomingSync' if app=='catalog' else 'movieIndexRebuild'
            rows=a.sql(f"SELECT i.id,i.trigger_status FROM cinema_job.xxl_job_info i JOIN cinema_job.xxl_job_group g ON g.id=i.job_group WHERE g.app_name='cinema-{app}' AND i.author='cinema' AND i.executor_handler='{handler}';").splitlines()
            assert len(rows)==1,'Expected one managed core definition per domain'
            job,status=map(int,rows[0].split())
            core.append((job,status))
            if status:
                admin('/jobinfo/stop',{'id':job})
            wait(lambda:a.sql(f"SELECT COUNT(*) FROM cinema_job.xxl_job_registry WHERE registry_key='cinema-{app}' AND update_time>DATE_SUB(NOW(),INTERVAL 25 SECOND);")!='0','domain executor heartbeat')
        a.passed('selected domain executors register; repeat initialization preserves task state and parameters')
        if args.domain != 'catalog':
            rebuild,search_group=add('search','movieIndexRebuild')
            old=state()['active_index']
            log=trigger(rebuild,search_group,'search')
            ready=state()
            assert ready['status']=='READY' and ready['active_index']!=old and ready.get('building_index') is None
            a.passed('real Search handler finishes a versioned rebuild and activates its completed alias')
            preserved=ready['active_index']
            es_stopped=True
            compose('stop','elasticsearch')
            trigger(rebuild,search_group,'search',expected=500)
            assert state()['active_index']==preserved
            compose('start','--wait','--wait-timeout','240','elasticsearch')
            es_stopped=False
            log=trigger(rebuild,search_group,'search')
            assert state()['status']=='READY'
            a.passed('ES outage yields a failed callback with the previous alias intact; recovery permits successful retry')
            scheduled,_=add('search','movieIndexRebuild',cron=True)
            before=latest(scheduled)
            admin('/jobinfo/start',{'id':scheduled})
            scheduled_log=int(wait(lambda:a.sql(f'SELECT id FROM cinema_job.xxl_job_log WHERE job_id={scheduled} AND id>{before} ORDER BY id LIMIT 1;'),'real cron firing',timeout=60))
            handled(scheduled_log,200)
            admin('/jobinfo/stop',{'id':scheduled})
            a.passed('a real temporary cron task executes the Search handler and records a successful business result')
            with sync_playwright() as p:
                browser=p.chromium.launch(channel='chrome',headless=True)
                try:
                    context=browser.new_context(locale='zh-CN',viewport={'width':1440,'height':1000})
                    context.add_cookies([{'name':c.name,'value':c.value,'domain':urllib.parse.urlsplit(args.admin_url).hostname,'path':c.path or '/'} for c in cookies])
                    page=context.new_page()
                    page.on('pageerror',lambda error:a.report['page_errors'].append(str(error)))
                    page.goto(args.admin_url+'/joblog/logDetailPage?id='+str(log),wait_until='domcontentloaded')
                    expect(page.locator('body')).to_contain_text('state=READY',timeout=30000)
                    page.screenshot(path=str(args.output/'search-job-execution.png'),full_page=True)
                    assert not a.report['page_errors']
                finally:
                    browser.close()
            a.passed('actual scheduler browser fetches the Search alias and cursor execution log')
        if args.domain != 'search':
            bad,catalog_group=add('catalog','movieUpcomingSync','51')
            baseline=a.http('/admin/movie/sync/status')['startedAt']
            trigger(bad,catalog_group,'catalog',expected=500)
            assert a.http('/admin/movie/sync/status')['startedAt']==baseline
            a.passed('invalid Catalog batch size fails before starting any external synchronization')
            sync,_=add('catalog','movieUpcomingSync','1')
            trigger(sync,catalog_group,'catalog',timeout=950)
            status=a.http('/admin/movie/sync/status')
            a.report['catalog_result']={key:status.get(key) for key in ('state','total','done','created','updated','skipped','partial','fail','startedAt','finishedAt')}
            assert status['state']=='COMPLETED' and not status['running'] and status['fail']==0
            a.passed('bounded real Catalog sync completes through the existing worker; resulting movie and OSS business data are retained')
        a.report['completed']=True
    finally:
        a.report['cleanup_completed']=False
        try:
            if es_stopped:
                compose('start','--wait','--wait-timeout','240','elasticsearch')
            for job in probes:
                admin('/jobinfo/remove',{'id':job})
            for job,status in core:
                if status:
                    admin('/jobinfo/start',{'id':job})
            a.report['cleanup_completed']=True
        finally:
            (args.output/'report.json').write_text(json.dumps(a.report,ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__':
    main()
