"""Create a new local domain schema/user without resetting existing databases or volumes."""
import argparse
from pathlib import Path
import re
import secrets
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('domain', choices=['notify','search','benefit','file','seata','trade'])
    args=parser.parse_args()
    path=ROOT/'.env'
    text=path.read_text(encoding='utf-8-sig')
    key=args.domain.upper()+'_DB_PASSWORD'
    settings=dict(line.split('=',1) for line in text.splitlines() if line and not line.startswith('#') and '=' in line)
    password=settings.get(key)
    if not password:
        password=secrets.token_urlsafe(48)
        if key in settings:
            text=re.sub(r'^'+key+r'=$',key+'='+password,text,flags=re.M)
        else:
            text=text.rstrip()+'\n'+key+'='+password+'\n'
        path.write_text(text,encoding='utf-8')
    if not re.fullmatch(r'[A-Za-z0-9+/=_-]+',password):
        raise ValueError('Domain password must be a nonempty base64/URL-safe value')
    schema='cinema_'+args.domain
    sql=(f"CREATE DATABASE IF NOT EXISTS `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
         f"CREATE USER IF NOT EXISTS '{schema}'@'%' IDENTIFIED BY '{password}';\n"
         f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{schema}'@'%';\n")
    result=subprocess.run(['docker','compose','exec','-T','mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4'],
        input=sql,text=True,encoding='utf-8',capture_output=True,cwd=ROOT)
    if result.returncode:
        raise RuntimeError('Local schema initialization failed; no schema or volume was reset')
    if args.domain in ('trade','benefit'):
        # Seata checks for undo_log while wrapping the DataSource, before Flyway can run.
        # Flyway subsequently creates its cleanup index; reruns here never ALTER existing tables.
        ddl=(ROOT/'deploy/local/seata/undo-log.sql').read_text(encoding='utf-8')
        result=subprocess.run(['docker','compose','exec','-T','mysql','sh','-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '+schema],
            input=ddl,text=True,encoding='utf-8',capture_output=True,cwd=ROOT)
        if result.returncode: raise RuntimeError('AT undo table bootstrap failed; existing data preserved')
    if args.domain=='seata':
        import base64
        text=path.read_text(encoding='utf-8-sig')
        for extra in ['SEATA_CONSOLE_PASSWORD','SEATA_SECURITY_SECRET']:
            if not re.search(r'^'+extra+r'=.+$',text,re.M):
                text += '\n'+extra+'='+base64.b64encode(secrets.token_bytes(32)).decode()+'\n'
        path.write_text(text,encoding='utf-8')
        ddl=(ROOT/'deploy/local/seata/mysql.sql').read_text(encoding='utf-8')+'\n'+(ROOT/'deploy/local/seata/upgrade-lock-keys.sql').read_text(encoding='utf-8')
        result=subprocess.run(['docker','compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot cinema_seata'],input=ddl,text=True,encoding='utf-8',capture_output=True,cwd=ROOT)
        if result.returncode: raise RuntimeError('Seata table initialization failed; existing data preserved')
    print('Schema/user ensured:',schema,'(existing password and data preserved)')

if __name__=='__main__': main()
