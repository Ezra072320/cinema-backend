"""Fail if a deployable service imports another service's implementation or shares its schema."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
services = ['cinema-identity', 'cinema-catalog', 'cinema-trade', 'cinema-ai', 'cinema-gateway']
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
owners = {}
for service in services:
    for path in (root / service / 'src/main/java').rglob('*.java'):
        text = path.read_text(encoding='utf-8-sig')
        match = re.search(r'package\s+([\w.]+);', text)
        if match:
            owners[match.group(1) + '.' + path.stem] = service
errors = []
for service in services:
    pom = ET.parse(root / service / 'pom.xml')
    for dep in pom.findall('.//m:dependencies/m:dependency', ns):
        if dep.findtext('m:artifactId', namespaces=ns) in services:
            errors.append(f'{service}: runtime dependency on another deployable service')
    for path in (root / service / 'src/main/java').rglob('*.java'):
        for imported in re.findall(r'import\s+(?:static\s+)?([\w.]+)', path.read_text(encoding='utf-8-sig')):
            if imported in owners and owners[imported] != service:
                errors.append(f'{path.relative_to(root)}: imports {owners[imported]} implementation {imported}')
    for path in (root / service / 'src/main').rglob('*'):
        if path.suffix in ('.java', '.xml'):
            if re.search(r'cinema_(?:identity|catalog|trade|ai)\s*\.', path.read_text(encoding='utf-8-sig')):
                errors.append(f'{path.relative_to(root)}: SQL references another service schema')
# Check the first matching gateway route for every public controller mapping.
# This catches ambiguous legacy URLs such as /api/user/scores (owned by trade).
gateway = (root / 'cinema-gateway/src/main/java/edu/czu/cinema/gateway/GatewayRoutes.java').read_text(encoding='utf-8')
routes = []
for match in re.finditer(r'\.route\(\s*"([^"]+)"\s*,\s*r\s*->\s*r\.path\(([^)]*)\)', gateway):
    owner = {'booking':'trade','upload':'identity'}.get(match.group(1),match.group(1))
    for pattern in re.findall(r'"([^"]+)"', match.group(2)):
        regex = re.escape(pattern).replace(r'/\*\*', '(?:/.*)?').replace(r'\*', '[^/]*')
        routes.append((re.compile('^' + regex + '$'), owner))
for service in services:
    if service == 'cinema-gateway':
        continue
    for path in (root / service / 'src/main/java').rglob('*Controller.java'):
        source = path.read_text(encoding='utf-8-sig')
        before_class = source.split('public class', 1)[0]
        prefix_match = re.search(r'@RequestMapping\("([^"]+)"\)', before_class)
        prefix = prefix_match.group(1) if prefix_match else ''
        if prefix.startswith('/internal/'):
            continue
        for annotation in re.findall(r'@(?:Get|Post|Put|Delete|Patch)Mapping\(([^)]*)\)', source):
            values = re.findall(r'"(/[^\"]*)"', annotation)
            for value in values:
                url = re.sub(r'\{[^}]+\}', 'sample', prefix + value)
                actual = next((owner for pattern, owner in routes if pattern.fullmatch(url)), None)
                expected = service.removeprefix('cinema-')
                if actual != expected:
                    errors.append(f'{url}: routes to {actual}, expected {expected}')
if errors:
    raise SystemExit('\n'.join(errors))
print('Architecture checks passed: independent executable dependencies and data access.')
