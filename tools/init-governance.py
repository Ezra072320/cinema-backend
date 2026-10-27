"""Ensure the local persistent seat-query rule without overwriting existing operator choices."""
from pathlib import Path
import json,secrets,urllib.parse,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1]
def main():
 path=ROOT/'.env';text=path.read_text(encoding='utf-8-sig');settings=dict(l.split('=',1)for l in text.splitlines()if l and not l.startswith('#')and '='in l)
 if not settings.get('SENTINEL_DASHBOARD_PASSWORD'):
  settings['SENTINEL_DASHBOARD_PASSWORD']=secrets.token_urlsafe(32);text+='\nSENTINEL_DASHBOARD_PASSWORD='+settings['SENTINEL_DASHBOARD_PASSWORD']+'\n';path.write_text(text,encoding='utf-8')
 def request(url,body=None):
  req=urllib.request.Request(url,data=urllib.parse.urlencode(body).encode()if body is not None else None)
  with urllib.request.urlopen(req,timeout=10)as response:return response.read().decode()
 token=json.loads(request('http://127.0.0.1:8848/nacos/v3/auth/user/login',{'username':settings.get('NACOS_USERNAME','nacos'),'password':settings['NACOS_PASSWORD']}))['accessToken']
 query={'dataId':'cinema-trade-seat-flow.json','group':'CINEMA_GOVERNANCE','accessToken':token}
 try:existing=request('http://127.0.0.1:8848/nacos/v1/cs/configs?'+urllib.parse.urlencode(query))
 except urllib.error.HTTPError as e:
  if e.code!=404:raise
  existing=''
 if not existing.strip():
  content=json.dumps([{'resource':'trade:seat-query','grade':1,'count':30,'limitApp':'default','strategy':0,'controlBehavior':0}])
  assert request('http://127.0.0.1:8848/nacos/v1/cs/configs',{**query,'content':content,'type':'json'}).strip()=='true'
 print('Persistent Nacos rule ensured; existing operator configuration and credentials preserved.')
if __name__=='__main__':main()
