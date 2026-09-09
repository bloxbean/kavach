import test from 'node:test';
import assert from 'node:assert/strict';
import { policiesAfterSignerRemoval } from '../src/signerPolicies.ts';

test('removing an unassigned key preserves signer identities and thresholds', () => {
  const policies = [{ role: 'Admin', threshold: 2, members: [0, 3, 10] }];
  assert.deepEqual(policiesAfterSignerRemoval(policies, 2), [{ role: 'Admin', threshold: 2, members: [0, 2, 9] }]);
  assert.deepEqual(policies[0].members, [0, 3, 10]);
});
test('assigned signer removal is rejected instead of weakening a policy', () => {
  assert.throws(() => policiesAfterSignerRemoval([{ threshold: 2, members: [0, 3] }], 3), /assignments/);
});
