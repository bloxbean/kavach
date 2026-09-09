import test from 'node:test';
import assert from 'node:assert/strict';
import { workflowStage, setupPhase, approvalPurpose, isFeePayerOnly, transactionRole } from '../src/approvalWorkflow.ts';
test('submission does not become complete before ledger confirmation', () => {
  assert.equal(workflowStage({ status: 'Awaiting intent proofs' }), 0);
  assert.equal(workflowStage({ status: 'Awaiting transaction signatures', transaction: '00' }), 1);
  assert.equal(workflowStage({ status: 'Ready', transaction: '00' }), 1);
  assert.equal(workflowStage({ status: 'Submitted', confirmed: false }), 2);
  assert.equal(workflowStage({ status: 'Submitted', confirmed: true }), 3);
});
test('setup phases retain enrollment and activation as distinct approvals', () => {
  assert.deepEqual([1, 6, 7, 8, 10].map(setupPhase), [0, 0, 1, 2, 2]);
  assert.notEqual(approvalPurpose('operation'), approvalPurpose('candidate'));
});

test('funding identity is not inferred to be an account authority', () => {
  const plan = { feePayer: { paymentKeyHash: 'fee' }, transactionAuthoritySigners: [] };
  assert.equal(isFeePayerOnly(plan), true);
  assert.equal(transactionRole(plan, 'fee'), 'Fee payer');
  assert.equal(transactionRole({ ...plan, transactionAuthoritySigners: ['fee'] }, 'fee'), 'Fee payer + transaction-based account authority');
  assert.equal(isFeePayerOnly({}), false);
  assert.equal(transactionRole({}, 'unknown'), 'Required wallet signer');
});
