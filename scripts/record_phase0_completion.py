#!/usr/bin/env python3
"""Record completion only from passing local, independent and live Phase 0 reports."""
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
rebuild = Path(sys.argv[1]).resolve()
if rebuild == root:
    raise SystemExit('Requires an independent rebuild directory')

def read(path):
    return json.loads(path.read_text())

def counts(folder, task):
    reports = list((folder / 'build/test-results' / task).glob('TEST-*.xml'))
    if not reports:
        raise SystemExit(f'Missing {task} reports in {folder}')
    result = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in result:
            result[key] += int(suite.get(key, 0))
    if result['tests'] == 0 or any(result[key] for key in ('failures', 'errors', 'skipped')):
        raise SystemExit(f'Incomplete {task}: {result}')
    return result

results = {task: counts(root, task) for task in ('test', 'toolchainAcceptance', 'integrationTest',
           'rewardCreditIntegrationTest', 'pairedRewardCreditIntegrationTest')}
independent = {task: counts(rebuild, task) for task in ('test', 'toolchainAcceptance')}
for task in independent:
    if independent[task] != results[task]:
        raise SystemExit(f'Independent {task} coverage differs')
manifest = read(root / 'build/phase0/build-manifest.json')
if manifest != read(rebuild / 'build/phase0/build-manifest.json'):
    raise SystemExit('Independent script/compiler manifest differs')
files = [root / name for name in ('build.gradle', 'settings.gradle', 'gradle.properties', 'gradle.lockfile', 'gradlew', 'gradlew.bat')]
for folder in ('src', 'gradle', 'protocol', 'conformance', 'toolchain', 'scripts'):
    files.extend(path for path in (root / folder).rglob('*') if path.is_file())
source_hashes = {}
for path in sorted(files):
    relative = path.relative_to(root)
    if path.read_bytes() != (rebuild / relative).read_bytes():
        raise SystemExit(f'Independent source differs: {relative}')
    source_hashes[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
cddl = read(root / 'build/phase0/cddl-validation.json')
if cddl['schemaSha256'] != source_hashes['protocol/v1/kavach.cddl'] or not cddl['invalidRootRejected']:
    raise SystemExit('Independent schema evidence is stale')
positive = read(root / 'build/phase0/positive-reward-evidence.json')
paired = read(root / 'build/phase0/paired-reward-evidence.json')
# The corresponding passing tests assert actual credits, node rejection and confirmed full withdrawal.
report = {'status': 'Phase 0 specification and feasibility qualified; not a production wallet',
          'date': '2026-09-07', 'localTests': results, 'independentTests': independent,
          'identicalCompiledTemplates': len(manifest['scripts']), 'sourceSha256': source_hashes,
          'schemaValidator': cddl['validator'], 'schemaFixtures': len(cddl['cases']),
          'positiveRewardEvidenceSha256': hashlib.sha256((root / 'build/phase0/positive-reward-evidence.json').read_bytes()).hexdigest(),
          'pairedRewardEvidenceSha256': hashlib.sha256((root / 'build/phase0/paired-reward-evidence.json').read_bytes()).hexdigest(),
          'qualificationScope': 'ADR-001 Phase 0. Integrated operation enforcement/budgets, production deployment and independent review remain later phases.'}
output = root / 'docs/phase0/evidence/completion-2026-09-07.json'
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({key: report[key] for key in ('status', 'localTests', 'identicalCompiledTemplates')}, indent=2))
