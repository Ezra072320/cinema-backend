"""Prepare local metrics credentials without replacing existing secrets."""
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]


def main():
    path = ROOT / '.env'
    text = path.read_text(encoding='utf-8-sig')
    values = {}
    for line in text.splitlines():
        if line and not line.startswith('#') and '=' in line:
            name, value = line.split('=', 1)
            values[name] = value
    for name in ('CINEMA_METRICS_KEY', 'GRAFANA_ADMIN_PASSWORD'):
        if not values.get(name):
            values[name] = secrets.token_urlsafe(40)
            lines = text.splitlines()
            replaced = False
            for index, line in enumerate(lines):
                if line.startswith(name + '='):
                    lines[index] = name + '=' + values[name]
                    replaced = True
            if not replaced:
                lines.append(name + '=' + values[name])
            text = '\n'.join(lines) + '\n'
    if len(values['CINEMA_METRICS_KEY']) < 32:
        raise ValueError('Existing metrics key must contain at least 32 characters')
    path.write_text(text, encoding='utf-8')
    directory = ROOT / '.work/observe'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'metrics-key').write_text(values['CINEMA_METRICS_KEY'], encoding='utf-8')
    print('Local metrics credentials prepared; existing values preserved.')


if __name__ == '__main__':
    main()
