# Browser authentication profiles, candidate 2

Status: implementation under qualification, not production or real-browser-wallet approval.
This adds module-specific evidence encodings. It does not redefine raw scheme 0 or change
`protocol/v1`'s archived first-module CDDL. The state configuration retains the existing
32-byte Ed25519 registry, six policies and defensive role independence.

The `BrowserModule` template has the original module's six deployment parameters followed
by `signingMode`: 1 for transaction witnesses or 2 for bounded COSE. The parameter is part
of the script identity and cannot be overridden in evidence. Unsupported modes reject.
ABI version remains 1: the outer module/genesis records and receipt bindings are unchanged.
The immutable-compatibility claim requires compiled and ledger tests for each operation.

Evidence occupies the existing constructor-0 `[credentialId, bytes]` slots. IDs remain
strictly ordered and unique. Operation proofs carry the matching scheme 1 or 2. Possession
proofs inherit the applied module mode; they cannot independently select a scheme.

For mode 1, evidence bytes are empty. Every named registry key must hash with Blake2b-224
to a payment key in the exact transaction body's required signers. The ledger verifies
those witnesses. The same final body commits to canonical intent/state/script data.
Creation/candidate installation names every key; rotations require every introduced key;
recovery completion requires introduced keys and the committed target spend threshold.
A fee sponsor's key cannot contribute unless it is explicitly a member of the selected role.
All signers must approve the same final transaction body. Any rebalance invalidates signatures.
The existing 16-required-signer transaction bound still applies: some large simultaneous
old/new-key combinations cannot fit this profile and must reject before signing.

For mode 2, the original proof is `address || signature` (93 or 121 bytes).
Candidate 2 additionally accepts `address || signature || 0x01` (94 or 122 bytes)
for the address-valued protected `kid` variant. Other suffixes reject. Address is a
29-byte enterprise key-payment address or a 57-byte base key-payment address (types 0, 2,
6). Its network ID must match the deployment and its payment credential must match the
registered public key's Blake2b-224 hash. Reward, pointer, Byron and script-payment signing
addresses are unsupported in this initial profile.

The validator reconstructs exactly:

- Original protected map: `{1: -8, "address": addressBytes}`.
- With suffix `0x01`: `{1: -8, 4: addressBytes, "address": addressBytes}`.
- Both maps use exactly the displayed field order and definite/minimal CBOR.
- Signing structure: `["Signature1", protectedBytes, h'', payload]`.
- Payload: the 32-byte intent digest, or the existing distinct genesis/configuration/target digest.

The adapter accepts optional COSE_Sign1 tag 18, the exact protected encoding, an empty
unprotected map or `{"hashed": false}`, a present exact payload and a 64-byte signature.
COSE_Key must contain kty=OKP, alg=EdDSA, crv=Ed25519 and 32-byte x, with no duplicates.
It may additionally contain label 2 (`kid`), required exactly when protected label 4 is
present; both must equal the signing address byte-for-byte. Other extra fields reject.
COSE_Key field order may vary because it is not the signed protected map.
Other variants fail explicitly. Returned keys identify enrollment candidates only; possession
and role assignment are separate. The on-chain verifier derives and verifies all signed bytes
it relies on; an off-chain parser cannot waive the signature or address checks.

The candidate-2 browser module has a new script hash. Existing deployed modules do not
gain kid support from an SDK upgrade; installing the new module requires the existing
admin policy and candidate possession proofs. Immutable validator sources and scheme 0
are unchanged. This is a module upgrade, not an automatic on-chain change.

These are deliberately bounded profiles, not general CIP-8 support. Real wallet captures
and compatibility testing must precede naming a wallet/version as supported. The SDK's
`BrowserAuthorization` checks detached signatures or the planned required-signers set;
it does not imply that the final transaction actually contains verified witnesses.
