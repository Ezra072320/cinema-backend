"""Initialize only the local scheduler's schema, credentials and missing cinema job definitions."""
from pathlib import Path
import hashlib,re,secrets,subprocess
ROOT=Path(__file__).resolve().parents[1]
def main():
 path=ROOT/'.env';text=path.read_text(encoding='utf-8-sig')
 settings=dict(line.split('=',1)for line in text.splitlines()if line and not line.startswith('#') and '='in line)
 for key in ['JOB_DB_PASSWORD','XXL_JOB_ACCESS_TOKEN','XXL_JOB_ADMIN_PASSWORD']:
  if not settings.get(key):
   settings[key]=secrets.token_urlsafe(40)
   if key in dict(line.split('=',1)for line in text.splitlines()if '='in line and not line.startswith('#')):text=re.sub(r'^'+key+r'=$',key+'='+settings[key],text,flags=re.M)
   else:text+='\n'+key+'='+settings[key]+'\n'
 path.write_text(text,encoding='utf-8')
 password=settings['JOB_DB_PASSWORD'];assert re.fullmatch('[A-Za-z0-9_=-]+',password)
 ddl=(ROOT/'deploy/local/job/mysql.sql').read_text(encoding='utf-8')
 sql=f"CREATE DATABASE IF NOT EXISTS cinema_job CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;CREATE USER IF NOT EXISTS 'cinema_job'@'%' IDENTIFIED BY '{password}';GRANT ALL PRIVILEGES ON cinema_job.* TO 'cinema_job'@'%';\n"+ddl
 hashed=hashlib.sha256(settings['XXL_JOB_ADMIN_PASSWORD'].encode()).hexdigest()
 sql+=f"\nINSERT IGNORE INTO xxl_job_user(id,username,password,role,permission) VALUES(1,'admin','{hashed}',1,NULL);"
 sql+="INSERT IGNORE INTO xxl_job_lock(lock_name) VALUES('schedule_lock');"
 sql+="INSERT INTO xxl_job_group(app_name,title,address_type,address_list,update_time) SELECT 'cinema-trade','影院票务',0,NULL,NOW() WHERE NOT EXISTS(SELECT 1 FROM xxl_job_group WHERE app_name='cinema-trade');"
 sql+="INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,alarm_email,schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,executor_handler,executor_param,executor_block_strategy,executor_timeout,executor_fail_retry_count,glue_type,glue_source,glue_remark,glue_updatetime,child_jobid,trigger_status,trigger_next_time) SELECT id,'超时订单与孤儿票对账',NOW(),NOW(),'cinema','','CRON','0 * * * * ?','DO_NOTHING','FIRST','orderExpiryReconcile','','SERIAL_EXECUTION',60,1,'BEAN','','影院初始任务',NOW(),'',1,UNIX_TIMESTAMP(DATE_ADD(NOW(),INTERVAL 1 MINUTE))*1000 FROM xxl_job_group g WHERE g.app_name='cinema-trade' AND NOT EXISTS(SELECT 1 FROM xxl_job_info i WHERE i.job_group=g.id AND i.executor_handler='orderExpiryReconcile');"
 sql+="INSERT INTO xxl_job_group(app_name,title,address_type,address_list,update_time) SELECT 'cinema-ai','影院AI客服',0,NULL,NOW() WHERE NOT EXISTS(SELECT 1 FROM xxl_job_group WHERE app_name='cinema-ai');"
 sql+="INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,alarm_email,schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,executor_handler,executor_param,executor_block_strategy,executor_timeout,executor_fail_retry_count,glue_type,glue_source,glue_remark,glue_updatetime,child_jobid,trigger_status,trigger_next_time) SELECT id,'AI过期服务单与180天历史清理',NOW(),NOW(),'cinema','','CRON','0 0 * * * ?','DO_NOTHING','FIRST','aiHistoryRetention','','SERIAL_EXECUTION',60,1,'BEAN','','影院AI历史保留任务',NOW(),'',1,UNIX_TIMESTAMP(DATE_ADD(NOW(),INTERVAL 10 MINUTE))*1000 FROM xxl_job_group g WHERE g.app_name='cinema-ai' AND NOT EXISTS(SELECT 1 FROM xxl_job_info i WHERE i.job_group=g.id AND i.executor_handler='aiHistoryRetention');"
 for app,title,handler,description,schedule,parameter,timeout in [
  ('cinema-catalog','影院影片资料','movieUpcomingSync','待映影片资料同步','0 0 5 * * ?','1',930),
  ('cinema-search','影院影片搜索','movieIndexRebuild','影片索引全量重建','0 0 4 ? * SUN','',330)]:
  sql+=f"INSERT INTO xxl_job_group(app_name,title,address_type,address_list,update_time) SELECT '{app}','{title}',0,NULL,NOW() WHERE NOT EXISTS(SELECT 1 FROM xxl_job_group WHERE app_name='{app}');"
  # External sync and full reindex are explicitly opt-in; existing schedules remain untouched.
  sql+=f"INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,alarm_email,schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,executor_handler,executor_param,executor_block_strategy,executor_timeout,executor_fail_retry_count,glue_type,glue_source,glue_remark,glue_updatetime,child_jobid,trigger_status,trigger_next_time) SELECT id,'{description}',NOW(),NOW(),'cinema','','CRON','{schedule}','DO_NOTHING','FIRST','{handler}','{parameter}','SERIAL_EXECUTION',{timeout},0,'BEAN','','影院手动启用任务',NOW(),'',0,0 FROM xxl_job_group g WHERE g.app_name='{app}' AND NOT EXISTS(SELECT 1 FROM xxl_job_info i WHERE i.job_group=g.id AND i.executor_handler='{handler}');"
 result=subprocess.run(['docker','compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4'],input=sql,text=True,encoding='utf-8',capture_output=True,cwd=ROOT)
 if result.returncode:raise RuntimeError('Scheduler initialization failed; no existing database or volume reset')
 print('Scheduler schema/user, private credentials and missing domain jobs ensured; existing preferences retained.')
if __name__=='__main__':main()
