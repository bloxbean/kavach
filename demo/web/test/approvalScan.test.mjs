import { test } from 'node:test';
import assert from 'node:assert/strict';
import QRCode from 'qrcode';
import jsQR from 'jsqr';
import { cameraLease, parsePhoneApproval } from '../src/approvalScan.ts';
const response = { version: 1, kind: 'approval', requestID: '78CD9640-A958-4945-92B6-5AAF126B0593', profile: 'kavach-cose-spend-v1', credentialID: 0,
  publicKey: 'ab'.repeat(32), digest: 'cd'.repeat(32), signature: '01'.repeat(180), key: '02'.repeat(42) };
const target = { id: 0, publicKey: response.publicKey, payload: response.digest };
test('decodes a phone-sized QR and preserves exact approval JSON', () => {
  const text = JSON.stringify(response);
  const code = QRCode.create(text, {errorCorrectionLevel: 'M'});
  const scale = 5, margin = 4, size = (code.modules.size + margin * 2) * scale;
  const pixels = new Uint8ClampedArray(size * size * 4).fill(255);
  for (let y = 0; y < code.modules.size; y++) for (let x = 0; x < code.modules.size; x++) {
    if (!code.modules.get(y, x)) continue;
    for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
      const offset = (((y + margin) * scale + dy) * size + (x + margin) * scale + dx) * 4;
      pixels[offset] = pixels[offset+1] = pixels[offset+2] = 0;
    }
  }
  assert.equal(jsQR(pixels, size, size, { inversionAttempts: 'attemptBoth' }).data, text);
  assert.deepEqual(parsePhoneApproval(text, target), response);
});
test('rejects unrelated, wrong-key, malformed and oversized QR payloads before import', () => {
  for (const changed of [{kind:'pair'}, {version:2}, {credentialID:1}, {publicKey:'00'.repeat(32)}, {digest:'00'.repeat(32)}, {profile:'kavach-raw-spend-v1'}, {signature:'bad'}, {key:null}, {requestID:'bad'}])
    assert.throws(() => parsePhoneApproval(JSON.stringify({...response, ...changed}), target));
  for (const text of ['null', '[]', '123', 'https://example.com', 'A'.repeat(8193)]) assert.throws(() => parsePhoneApproval(text, target));
});
test('camera is released once, including when permission arrives after close', () => {
  let stops = 0;
  const stream = { getTracks: () => [{stop: () => stops++}, {stop: () => stops++}] };
  const lease = cameraLease(); assert.equal(lease.accept(stream), true);
  lease.close(); lease.close(); assert.equal(stops, 2);
  const late = cameraLease(); late.close(); assert.equal(late.accept(stream), false); assert.equal(stops, 4);
});

test('accepts policy activation approvals and keeps their digest binding', () => {
  const operation = { ...response, profile: 'kavach-cose-policy-v1' };
  const candidate = { ...operation, digest: 'ef'.repeat(32) };
  assert.deepEqual(parsePhoneApproval(JSON.stringify(operation), target), operation);
  assert.deepEqual(parsePhoneApproval(JSON.stringify(candidate), { ...target, payload: candidate.digest }), candidate);
  assert.throws(() => parsePhoneApproval(JSON.stringify(candidate), target), /different key or request/);
  assert.throws(() => parsePhoneApproval(JSON.stringify({ ...operation, profile: 'unknown-policy-v2' }), target), /Unsupported/);
});
