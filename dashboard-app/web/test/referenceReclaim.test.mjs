import test from 'node:test';
import assert from 'node:assert/strict';
import { referenceReclaim } from '../src/referenceReclaim.ts';
const owner = '12'.repeat(28);
const reference = { hosting: 'vault', available: true, reclaimable: true, activeRequired: true,
  scriptHash: '34'.repeat(28), publisherKeyHash: owner, transactionHash: '56'.repeat(32), outputIndex: 3 };
test('reclaim binds exact vault output after explicit acknowledgement and matching publisher selection', () => {
  assert.deepEqual(referenceReclaim(reference, `60${owner}`, true), {
    scriptHash: reference.scriptHash, transactionHash: reference.transactionHash, outputIndex: 3, acknowledge: true,
  });
  assert.equal(referenceReclaim({ ...reference, activeRequired: false }, `00${owner}${'78'.repeat(28)}`, true).outputIndex, 3);
});
test('reclaim preflight rejects unacknowledged, stale, substituted owner and historical outputs', () => {
  assert.throws(() => referenceReclaim(reference, `60${owner}`, false), /Acknowledge/);
  assert.throws(() => referenceReclaim(reference, `60${'99'.repeat(28)}`, true), /publisher wallet/);
  assert.throws(() => referenceReclaim(reference, `70${owner}`, true), /publisher wallet/);
  for (const patch of [{ available: false }, { hosting: 'legacy' }, { hosting: 'publisher' }, { reclaimable: false }])
    assert.throws(() => referenceReclaim({ ...reference, ...patch }, `60${owner}`, true), /available publisher-vault/);
  for (const patch of [{ transactionHash: 'invalid' }, { outputIndex: -1 }, { outputIndex: 1.5 }, { publisherKeyHash: undefined }])
    assert.throws(() => referenceReclaim({ ...reference, ...patch }, `60${owner}`, true), /Incomplete reference/);
});
