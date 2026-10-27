"""Local HTTP/OSS acceptance. Creates one user and one image and removes both.

Run mvn verify first (provides the OSS SDK test classpath), then run with JAVA_HOME=JDK21.
Credentials come from the private .env and are never printed or passed as command arguments.
"""
import argparse
import base64
from datetime import date
import json
import os
import re
import secrets
import subprocess
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

from ai_local_acceptance import Acceptance, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/file-local-acceptance')
    args = parser.parse_args()
    probe = Acceptance(args.base_url, args.output)
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    report_path = (ROOT / 'cinema-framework/cinema-file-support/target/surefire-reports/'
                   'TEST-edu.czu.cinema.file.OssImageFilesTest.xml')
    classpath = ET.parse(report_path).getroot().find("properties/property[@name='java.class.path']").get('value')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe') if os.environ.get('JAVA_HOME') else 'java'
    image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
    stored_key = None
    checks_passed = False
    oss_cleaned = False

    def upload(content, name, mime, authenticated=True):
        boundary = 'cinema-' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                f'Content-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
        headers = {'Content-Type': 'multipart/form-data; boundary=' + boundary}
        if authenticated:
            headers['Authorization'] = probe.token
        request = urllib.request.Request(probe.base + '/api/file/upload', data=body, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    try:
        password = secrets.token_hex(7)
        probe.http('/user/register', {'name': probe.name, 'password': password, 'age': 24, **probe.captcha()})
        probe.uid = int(probe.sql("SELECT id FROM cinema_identity.user WHERE name='" + probe.name + "'"))
        probe.token = probe.http('/user/login', {'name': probe.name, 'password': password, **probe.captcha()})
        status, body = upload(image, 'probe.png', 'image/png', authenticated=False)
        assert status in (401, 403) or body.get('code') != 1
        probe.passed('anonymous upload is rejected')
        status, body = upload(b'<html>not an image</html>', 'poster.jpg', 'image/jpeg')
        assert body.get('code') != 1
        probe.passed('HTML disguised as a JPEG is rejected')
        status, body = upload(image, 'probe.html', 'text/html')
        assert status == 200 and body.get('code') == 1, 'Valid image upload failed'
        url = urlsplit(body['data'])
        endpoint = settings.get('ALIYUN_OSS_ENDPOINT', 'https://oss-cn-chengdu.aliyuncs.com')
        host = urlsplit(endpoint if '://' in endpoint else 'https://' + endpoint).hostname
        bucket = settings.get('ALIYUN_OSS_BUCKET', 'cinema-bucket')
        assert url.scheme == 'https' and url.hostname == bucket + '.' + host, 'Unexpected OSS destination'
        stored_key = url.path.lstrip('/')
        assert re.fullmatch(r'cinema/\d{4}/\d{2}/\d{2}/[a-f0-9]{32}\.png', stored_key)
        with urllib.request.urlopen(body['data'], timeout=25) as response:
            assert response.read() == image
            assert response.headers.get_content_type() == 'image/png'
        probe.passed('Gateway upload uses the existing OSS bucket, server image type and legacy URL format')
        # Read stored posters only; no synchronization job or metadata update is triggered.
        old_urls = probe.sql("SELECT poster_url FROM cinema_catalog.movie WHERE poster_url LIKE 'https://"
                             + bucket + '.' + host + "/%' LIMIT 2").splitlines()
        assert old_urls, 'No existing OSS poster available for compatibility check'
        for old_url in old_urls:
            with urllib.request.urlopen(old_url, timeout=25) as response:
                assert response.status == 200
                assert response.headers.get_content_type().startswith('image/')
                assert response.read(32)
        probe.passed('existing OSS poster URLs remain readable without migration')
        checks_passed = True
    finally:
        try:
            if stored_key:
                cleanup_env = os.environ.copy()
                cleanup_env.update({key: settings.get(key, '') for key in
                    ('ALIYUN_OSS_AK','ALIYUN_OSS_SK','ALIYUN_OSS_ENDPOINT','ALIYUN_OSS_BUCKET')})
                cleanup_env['ALIYUN_OSS_ENDPOINT'] = endpoint
                cleanup_env['ALIYUN_OSS_BUCKET'] = bucket
                cleanup_env['CINEMA_OSS_PROBE_KEY'] = stored_key
                result = subprocess.run([java, '--class-path', classpath, str(ROOT / 'tools/OssProbeCleanup.java')],
                    env=cleanup_env, capture_output=True, timeout=60)
                assert result.returncode == 0, 'Unable to remove acceptance OSS object; check local probe state'
                oss_cleaned = True
                probe.passed('temporary OSS object removed using the OSS SDK')
        finally:
            if probe.uid is not None:
                subprocess.run(['docker','compose','exec','-T','redis','sh','-c',
                    'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"','redis-cli',
                    'UNLINK',f'upload:quota:{probe.uid}:' + date.today().isoformat()],
                    cwd=ROOT, capture_output=True, check=True)
            probe.cleanup()
            probe.report['completed'] = checks_passed and oss_cleaned
            (args.output / 'report.json').write_text(json.dumps(probe.report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
