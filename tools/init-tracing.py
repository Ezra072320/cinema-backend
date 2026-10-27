"""Prepare the pinned SkyWalking agent and private Horizon login configuration."""
from pathlib import Path
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = '9.8.0'
UI_IMAGE = 'apache/skywalking-ui:horizon-1.0.0@sha256:eb08dcf2fe28fc805aaaeb48f25e0b67d141672a0b9a3a174bbfc4135dd6127d'


def main():
    env = ROOT / '.env'
    text = env.read_text(encoding='utf-8-sig')
    values = dict(line.split('=', 1) for line in text.splitlines()
                  if line and not line.startswith('#') and '=' in line)
    for name in ('SKYWALKING_AUTH_TOKEN', 'SKYWALKING_UI_PASSWORD'):
        if not values.get(name):
            values[name] = secrets.token_urlsafe(40)
            lines = text.splitlines()
            if any(line.startswith(name + '=') for line in lines):
                lines = [name + '=' + values[name] if line.startswith(name + '=') else line for line in lines]
            else:
                lines.append(name + '=' + values[name])
            text = '\n'.join(lines) + '\n'
    env.write_text(text, encoding='utf-8')
    directory = ROOT / '.work/skywalking'
    directory.mkdir(parents=True, exist_ok=True)
    archive_name = f'apache-skywalking-java-agent-{VERSION}.tgz'
    base = f'https://downloads.apache.org/skywalking/java-agent/{VERSION}/'
    for name in (archive_name, archive_name + '.sha512'):
        destination = directory / name
        if not destination.exists():
            temporary = destination.with_suffix(destination.suffix + '.part')
            with urllib.request.urlopen(base + name, timeout=120) as response, temporary.open('wb') as target:
                shutil.copyfileobj(response, target)
            temporary.replace(destination)
    expected = (directory / (archive_name + '.sha512')).read_text().split()[0].lower()
    if hashlib.sha512((directory / archive_name).read_bytes()).hexdigest() != expected:
        raise ValueError('SkyWalking agent SHA512 mismatch')
    extracted = directory / f'agent-{VERSION}'
    with tarfile.open(directory / archive_name, 'r:gz') as archive:
        archive.extractall(extracted, filter='data')
    source = extracted / 'skywalking-agent'
    for kind in ('mvc', 'gateway'):
        destination = directory / f'{kind}-{VERSION}'
        shutil.copytree(source, destination, dirs_exist_ok=True)
        plugins = ['apm-trace-ignore-plugin-' + VERSION + '.jar']
        if kind == 'gateway':
            plugins.append('apm-spring-cloud-gateway-4.x-plugin-' + VERSION + '.jar')
        else:
            plugins.append('apm-springmvc-annotation-6.x-plugin-' + VERSION + '.jar')
        for plugin in plugins:
            shutil.copyfile(source / 'optional-plugins' / plugin, destination / 'plugins' / plugin)
        # This plugin reads a file or a JVM system property, not SW_AGENT_TRACE_IGNORE_PATH.
        (destination / 'config/apm-trace-ignore-plugin.config').write_text(
            'trace.ignore_path=**/actuator/**,**/actuator\n', encoding='utf-8')
    # Keep app dependencies unchanged: build a small agent-only SPI extension.
    # Its two reflective fields are version-pinned and verified before any startup.
    privacy_source = ROOT / 'tools/tracing'
    privacy_classes = directory / 'privacy-classes'
    privacy_classes.mkdir(exist_ok=True)
    agent_jar = source / 'skywalking-agent.jar'
    result = subprocess.run(['javac', '--release', '21', '-cp', str(agent_jar), '-d', str(privacy_classes),
                             str(privacy_source / 'CinemaTracePrivacy.java'),
                             str(privacy_source / 'CinemaTracePrivacyCheck.java')], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Privacy extension compilation failed; Java 21+ is required: ' + result.stderr)
    result = subprocess.run(['java', '-cp', str(privacy_classes) + os.pathsep + str(agent_jar),
                             'cinema.observe.CinemaTracePrivacyCheck'], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Pinned agent privacy verification failed: ' + result.stderr)
    # The intentional corrupt-segment check emits a warning; show its final verdict.
    print(result.stdout.strip().splitlines()[-1])
    privacy_jar = directory / 'cinema-trace-privacy.jar'
    result = subprocess.run(['jar', '--create', '--file', str(privacy_jar), '-C', str(privacy_classes),
                             'cinema/observe/CinemaTracePrivacy.class', '-C', str(privacy_source), 'META-INF/services'],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Privacy extension packaging failed')
    for kind in ('mvc', 'gateway'):
        shutil.copyfile(privacy_jar, directory / f'{kind}-{VERSION}/plugins/cinema-trace-privacy.jar')
    # Password goes through stdin and only its Argon2 hash is captured.
    hash_script = "const fs=require('fs'); require('/app/node_modules/argon2').hash(fs.readFileSync(0,'utf8')).then(v=>process.stdout.write(v));"
    result = subprocess.run(['docker', 'run', '--rm', '-i', '--entrypoint', 'node', UI_IMAGE, '-e', hash_script],
                            input=values['SKYWALKING_UI_PASSWORD'], capture_output=True, text=True)
    if result.returncode or not result.stdout.startswith('$argon2id$'):
        raise RuntimeError('Unable to generate private Horizon password hash')
    # JSON is valid YAML; no interpolated credentials enter tracked configuration.
    config = {
        'server': {'host': '0.0.0.0', 'port': 8081},
        'oap': {'queryUrl': 'http://skywalking-oap:12800', 'adminUrl': 'http://skywalking-oap:17128'},
        'auth': {'backend': 'local', 'local': {'users': [
            {'username': 'cinema', 'passwordHash': result.stdout, 'roles': ['admin']}]}},
        'session': {'ttlMinutes': 60, 'cookieName': 'cinema_skywalking_session', 'cookieSecure': False},
        'ai': {'enabled': False}, 'mcp': {'enabled': False}, 'debugLog': {'enabled': False},
    }
    (directory / 'horizon.yaml').write_text(json.dumps(config, indent=2), encoding='utf-8')
    print('SkyWalking agent checksum verified; MVC/Gateway plugins and private Horizon login prepared.')


if __name__ == '__main__':
    main()
