#!/usr/bin/env python3
"""Independent CDDL check. Requires cddl 0.12.14 on PATH; no network or installation."""
import hashlib
import json
import pathlib
import subprocess
import tempfile

version = subprocess.run(['cddl', '--version'], capture_output=True, text=True)
if 'cddl tool version 0.12.14' not in version.stdout + version.stderr:
    raise SystemExit('Requires independently pinned cddl 0.12.14')

root = pathlib.Path(__file__).resolve().parents[1]
schema = (root / 'protocol/v1/kavach.cddl').read_text()
cases = [('intent-envelope', str(v['actionTag']), v['cbor'])
         for v in json.loads((root / 'conformance/v1/intent-vectors.json').read_text())]
for vector in json.loads((root / 'conformance/v1/edge-vectors.json').read_text()):
    cases.append(('intent-envelope', vector['name'], vector['cbor']))
    if 'resolvedInputValueCbor' in vector:
        cases.append(('resolved-ledger-value', vector['name'] + '-value', vector['resolvedInputValueCbor']))
cases.append(('account-state', 'state', (root / 'conformance/v1/state.hex').read_text().strip()))
for name, vector in json.loads((root / 'conformance/v1/proof-domains.json').read_text()).items():
    cases.append((name, name, vector['cbor']))
setup = json.loads((root / 'conformance/v1/genesis-possession.json').read_text())
cases.append(('genesis-module-redeemer', 'genesis-possession', setup['invocationCbor']))
for vector in json.loads((root / 'conformance/v1/intent-vectors.json').read_text()):
    if 'resolvedInputValueCbor' in vector:
        cases.append(('resolved-ledger-value', 'whole-input-value', vector['resolvedInputValueCbor']))
cases.append(('account-state', 'pending-state', (root / 'conformance/v1/pending-state.hex').read_text().strip()))
results = []
with tempfile.TemporaryDirectory(prefix='kavach-cddl-') as folder:
    temp = pathlib.Path(folder)
    for rule, name, cbor in cases:
        spec = temp / 'root.cddl'
        spec.write_text('root = ' + rule + '\n' + schema)
        instance = temp / 'vector.cbor'
        instance.write_bytes(bytes.fromhex(cbor))
        result = subprocess.run(['cddl', str(spec), 'validate', str(instance)], capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(name + ': ' + result.stdout + result.stderr)
        results.append({'rule': rule, 'case': name, 'accepted': True})
    # A root integer is not a valid typed intent, even though it is valid CBOR/Data.
    spec.write_text('root = intent-envelope\n' + schema)
    instance.write_bytes(bytes([0]))
    rejected = subprocess.run(['cddl', str(spec), 'validate', str(instance)], capture_output=True, text=True)
    if rejected.returncode == 0:
        raise SystemExit('Invalid envelope unexpectedly passed independent schema validation')
report = {'validator': 'cddl 0.12.14', 'schemaSha256': hashlib.sha256(schema.encode()).hexdigest(),
          'cases': results, 'invalidRootRejected': True,
          'scope': 'Structural CDDL validation; semantic and canonical serialization rules are checked separately by Gradle tests'}
out = root / 'build/phase0/cddl-validation.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2) + '\n')
print(f'Validated {len(cases)} fixtures and one structural rejection')
