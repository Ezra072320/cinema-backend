"""Verify the first captcha after renderer warmup; optionally restart the same Identity container.

No accounts are created. Each generated captcha ticket is removed explicitly.
The routing probe waits for an anonymous 401 before measuring the first PNG, so
service discovery convergence is distinct from cold font/ImageIO initialization.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from ai_local_acceptance import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restart', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/cold-captcha-acceptance')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'completed': False, 'restarted_same_container': args.restart}
    ticket = uuid.uuid4().hex
    try:
        state = subprocess.check_output(['docker', 'inspect', 'cinema-cloud-identity-1'], text=True)
        previous = json.loads(state)[0]
        expected = subprocess.check_output(['docker', 'image', 'inspect', 'cinema/identity:local', '--format', '{{.Id}}'], text=True).strip()
        assert previous['Image'] == expected, 'Deploy the current Identity image before cold validation'
        if args.restart:
            result = subprocess.run(['docker', 'compose', 'restart', '--no-deps', 'identity'], cwd=ROOT, capture_output=True)
            assert result.returncode == 0, 'Identity restart failed'
        until = time.monotonic() + 240
        while time.monotonic() < until:
            state = json.loads(subprocess.check_output(['docker', 'inspect', 'cinema-cloud-identity-1'], text=True))[0]
            assert state['Id'] == previous['Id'], 'Restart recreated the container'
            if state.get('State', {}).get('Health', {}).get('Status') == 'healthy':
                break
            time.sleep(2)
        else:
            raise AssertionError('Identity did not become ready')
        logs = subprocess.run(['docker', 'logs', '--since', state['State']['StartedAt'], 'cinema-cloud-identity-1'],
                              capture_output=True, text=True, encoding='utf-8')
        events = []
        for line in logs.stdout.splitlines():
            try:
                events.append(json.loads(line))
            except ValueError:
                pass
        warmed = [event for event in events if event.get('message') == 'Captcha image renderer is ready']
        assert warmed, 'Renderer startup warmup did not execute'
        report['renderer_ready_at'] = warmed[-1]['@timestamp']
        until = time.monotonic() + 90
        while time.monotonic() < until:
            try:
                urllib.request.urlopen('http://localhost:8088/api/admin/info', timeout=10).close()
            except urllib.error.HTTPError as error:
                if error.code == 401:
                    break
            time.sleep(2)
        else:
            raise AssertionError('Identity routing did not converge')
        started = time.monotonic()
        with urllib.request.urlopen('http://localhost:8088/api/captcha/image?ticket=' + ticket, timeout=15) as response:
            image = response.read()
            report['status'] = response.status
            report['elapsed_ms'] = round((time.monotonic() - started) * 1000, 2)
            assert response.status == 200 and response.headers.get_content_type() == 'image/png'
            assert 'no-store' in response.headers.get('Cache-Control', '')
            assert image.startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid captcha PNG'
        assert report['elapsed_ms'] < 5000, 'Cold captcha exceeded Gateway identity timeout'
        report['completed'] = True
        print('PASS first captcha after startup warmup: HTTP 200, valid uncached PNG, ' + str(report['elapsed_ms']) + ' ms')
    finally:
        cleanup = subprocess.run(['docker', 'compose', 'exec', '-T', 'redis', 'sh', '-c',
                                 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"',
                                 'redis-cli', 'UNLINK', 'captcha:' + ticket], cwd=ROOT, capture_output=True)
        report['ticket_cleaned'] = cleanup.returncode == 0
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
