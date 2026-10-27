"""Build selected, already verified JARs without retaining previous application layers.

Uses the normal Dockerfile's empty runtime-base stage and one temporary image tag
per role. It does not run Maven or copy artifacts into another host directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ROLES = ('identity', 'catalog', 'trade', 'ai', 'gateway', 'notify', 'search', 'benefit')


def command(*args, capture=False):
    result = subprocess.run(list(args), cwd=ROOT, text=True, encoding='utf-8',
                            capture_output=capture, check=True)
    return result.stdout.strip() if capture else None


def space_guard():
    free = shutil.disk_usage(ROOT).free / 2**30
    if free < 8:
        raise SystemExit(f'Build stopped: only {free:.2f} GiB free; at least 8 GiB is required.')
    return round(free, 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('roles', choices=ROLES, nargs='+')
    parser.add_argument('--runtime-image', default='eclipse-temurin:21-jre',
                        help='Explicit runtime base; defaults to the normal Dockerfile Java 21 image')
    parser.add_argument('--report', type=Path, default=ROOT / '.work/local-runtime-build.json')
    args = parser.parse_args()
    roles = list(dict.fromkeys(args.roles))
    command('docker', 'info', '--format', '{{.ServerVersion}}', capture=True)
    report = {'completed': False, 'free_gib_before': space_guard(),
              'runtime_image': args.runtime_image, 'images': []}
    artifacts = {}
    for role in roles:
        jar = ROOT / f'cinema-{role}/target/cinema-{role}-0.1.0-exec.jar'
        if not jar.is_file():
            raise SystemExit(f'Run Maven verify first: missing {jar.name}')
        with jar.open('rb') as stream:
            artifacts[role] = (jar, hashlib.file_digest(stream, 'sha256').hexdigest())
    base = 'cinema/runtime-base:local'
    fingerprint = hashlib.sha256((ROOT / 'Dockerfile').read_bytes() + b'\n' + args.runtime_image.encode()).hexdigest()
    check = subprocess.run(['docker', 'image', 'inspect', base, '--format', '{{json .Config.Labels}}'],
                           cwd=ROOT, capture_output=True, text=True)
    labels = json.loads(check.stdout) or {} if check.returncode == 0 else {}
    if labels.get('cinema.runtime-source') != fingerprint:
        command('docker', 'build', '--target', 'runtime-base', '--build-arg',
                'RUNTIME_IMAGE=' + args.runtime_image, '--label',
                'cinema.runtime-source=' + fingerprint, '-t', base, '-f', 'Dockerfile', '.')
    for role, (jar, digest) in artifacts.items():
        space_guard()
        candidate = f'cinema/{role}:local-candidate'
        command('docker', 'build', '--build-context', 'packaged=' + str(jar.parent),
                '--build-arg', 'APP_JAR=' + jar.name, '--label', 'cinema.app-sha256=' + digest,
                '-t', candidate, '-f', 'deploy/local/Dockerfile.prebuilt', '.')
        actual = command('docker', 'run', '--rm', '--network', 'none', '--entrypoint',
                         'sha256sum', candidate, '/app/app.jar', capture=True).split()[0]
        if actual != digest:
            raise SystemExit(f'{role} packaged JAR differs from the verified local artifact')
        command('docker', 'tag', candidate, f'cinema/{role}:local')
        command('docker', 'image', 'rm', candidate)
        details = json.loads(command('docker', 'image', 'inspect', f'cinema/{role}:local',
                                     '--format', '{{json .}}', capture=True))
        report['images'].append({'role': role, 'image_id': details['Id'],
                                 'size_mib': round(details['Size'] / 2**20, 1), 'jar_sha256': digest})
    report.update(completed=True, free_gib_after=space_guard())
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
