"""Fail if a deployable service imports another service's implementation or shares its schema."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
services = ['cinema-identity', 'cinema-catalog', 'cinema-trade', 'cinema-ai', 'cinema-gateway', 'cinema-notify', 'cinema-search', 'cinema-benefit']
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
owners = {}
for service in services:
    for path in (root / service / 'src/main/java').rglob('*.java'):
        text = path.read_text(encoding='utf-8-sig')
        match = re.search(r'package\s+([\w.]+);', text)
        if match:
            owners[match.group(1) + '.' + path.stem] = service
errors = []

# Follow reactor dependencies rather than checking only the executable POM's direct imports.
# A starter must not silently bring JDBC/MVC into Gateway, or OSS into unrelated applications.
reactor = {}
def read_reactor(directory):
    pom = ET.parse(directory / 'pom.xml')
    artifact = pom.findtext('m:artifactId', namespaces=ns)
    reactor[artifact] = (directory, pom)
    for module in pom.findall('m:modules/m:module', ns):
        read_reactor(directory / module.text)
read_reactor(root)

def dependency_closure(artifact, seen=None):
    seen = set() if seen is None else seen
    if artifact in seen:
        return seen
    seen.add(artifact)
    if artifact in reactor:
        for dep in reactor[artifact][1].findall('m:dependencies/m:dependency', ns):
            if dep.findtext('m:scope', namespaces=ns) not in ('test', 'provided'):
                dependency_closure(dep.findtext('m:artifactId', namespaces=ns), seen)
    return seen

gateway_forbidden = {'cinema-common', 'spring-boot-starter-web', 'spring-webmvc',
    'spring-boot-starter-jdbc', 'mybatis-plus-spring-boot3-starter', 'mysql-connector-j',
    'aliyun-sdk-oss', 'rocketmq-client-java', 'cinema-starter-security', 'cinema-starter-data', 'cinema-file-support'}
for forbidden in sorted(dependency_closure('cinema-gateway') & gateway_forbidden):
    errors.append(f'cinema-gateway: forbidden transitive dependency {forbidden}')
for service in services:
    closure = dependency_closure(service)
    if 'cinema-common' in closure:
        errors.append(f'{service}: use explicit capability modules instead of cinema-common')
    if service not in ('cinema-identity', 'cinema-catalog') and 'aliyun-sdk-oss' in closure:
        errors.append(f'{service}: OSS is only owned by file support callers')
    if service != 'cinema-search' and 'elasticsearch-java' in closure:
        errors.append(f'{service}: Elasticsearch SDK belongs only to Search')
    if service not in ('cinema-trade','cinema-ai','cinema-catalog','cinema-search') and ('xxl-job-core' in closure or 'redisson' in closure):
        errors.append(f'{service}: scheduler/Redisson belongs only to domains with job handlers')
    if service not in ('cinema-trade','cinema-benefit') and 'seata-spring-boot-starter' in closure:
        errors.append(f'{service}: Seata belongs only to transaction participants')
    for other in (set(services) - {service}) & closure:
        errors.append(f'{service}: transitive runtime dependency on {other}')

classes = {}
for artifact, (directory, _) in reactor.items():
    for path in (directory / 'src/main/java').rglob('*.java'):
        source = path.read_text(encoding='utf-8-sig')
        package = re.search(r'package\s+([\w.]+);', source)
        if not package:
            continue
        name = package.group(1) + '.' + path.stem
        if name in classes:
            errors.append(f'duplicate production class: {name}')
        classes[name] = artifact
        if artifact == 'cinema-core' and re.search(
                r'import\s+(?:org\.springframework|com\.baomidou|com\.aliyun|edu\.czu\.cinema\.domain)\.', source):
            errors.append(f'{path.relative_to(root)}: core must remain independent of runtimes and domain entities')
        if 'import com.aliyun.oss.' in source and artifact != 'cinema-file-support':
            errors.append(f'{path.relative_to(root)}: OSS SDK implementation must stay in file support')

allowed_entities = set(json.loads((root / 'tools/architecture-policy.json').read_text(encoding='utf-8'))['legacyApiEntities'])
for name, artifact in classes.items():
    if artifact == 'cinema-api' and '.entity.' in name and name not in allowed_entities:
        errors.append(f'{name}: new persistence entity belongs in its owning application')
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
            if re.search(r'cinema_(?:identity|catalog|trade|ai|notify|search|benefit)\s*\.', path.read_text(encoding='utf-8-sig')):
                errors.append(f'{path.relative_to(root)}: SQL references another service schema')
# Check the first matching gateway route for every public controller mapping.
# This catches ambiguous legacy URLs such as /api/user/scores (owned by trade).
gateway = (root / 'cinema-gateway/src/main/java/edu/czu/cinema/gateway/GatewayRoutes.java').read_text(encoding='utf-8')
routes = []
for match in re.finditer(r'\.route\(\s*"([^"]+)"\s*,\s*r\s*->\s*r\.path\(([^)]*)\)', gateway):
    owner = {'booking':'trade','upload':'identity','notify-socket':'notify'}.get(match.group(1),match.group(1))
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
                if url.startswith('/internal/'): continue
                actual = next((owner for pattern, owner in routes if pattern.fullmatch(url)), None)
                expected = service.removeprefix('cinema-')
                if actual != expected:
                    errors.append(f'{url}: routes to {actual}, expected {expected}')
if errors:
    raise SystemExit('\n'.join(errors))
print('Architecture checks passed: independent executable dependencies and data access.')
