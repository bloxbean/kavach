import { test } from 'node:test';
import assert from 'node:assert/strict';
import { walletProof } from '../src/walletProof.ts';
const hash = 'ab'.repeat(28);
const proof = { id: 1, purpose: 'genesis', paymentKeyHash: hash };
const plan = { payloads: [proof] };
test('unlisted HD key requires explicit choice and exact enterprise credential', () => {
  assert.throws(() => walletProof(plan, [], ''), /Choose the pending key/);
  assert.deepEqual(walletProof(plan, [], 'genesis:1'), { proof, address: `60${hash}` });
  assert.throws(() => walletProof(plan, [], 'genesis:2'));
});
test('listed addresses remain usable and selected keys cannot silently fall back', () => {
  const address = `00${hash}${'cd'.repeat(28)}`;
  assert.equal(walletProof(plan, [address], '').address, address);
  assert.throws(() => walletProof(plan, [address], 'operation:1'));
  assert.throws(() => walletProof({payloads: [{...proof, paymentKeyHash: 'bad'}]}, [], 'genesis:1'));
});
