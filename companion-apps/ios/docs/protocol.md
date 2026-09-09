# Companion exchange v1

This is a transport/integration candidate, not a change to Kavach's wire protocol.
Cardano authorization still comes from the enrolled key and installed module. Pairing
authorizes a source of requests; it does not grant that source spending authority.

## Pairing

UTF-8 JSON, at most 1024 bytes:

```json
{"version":1,"kind":"pair","name":"Kavach on Mac","publicKey":"<32-byte lowercase hex Ed25519 key>"}
```

Names use 1–40 printable ASCII characters. They are untrusted display labels. The
phone displays BLAKE2b-256 of the desktop public key as its fingerprint and requires
explicit comparison with the desktop before pinning. At most eight desktops may be
paired. Manual QR/file transfer does not establish encrypted transport. Future network
pairing needs a separately reviewed mutual-authentication/encryption protocol.

## Request

UTF-8 JSON, at most 8192 bytes:

```json
{"version":1,"kind":"request","senderPublicKey":"<pinned desktop key>","body":"<base64 exact UTF-8 body bytes>","signature":"<64-byte hex signature>"}
```

The desktop signs `UTF8("YANO_COMPANION_REQUEST_V1") || 0x00 || bodyBytes` with
Ed25519. Verification precedes body decoding. JSON serialization is not reconstructed
for signature verification; the exact base64-decoded bytes are authenticated.

Body, at most 4096 bytes:

```json
{"id":"<UUID>","profile":"kavach-cose-spend-v1","intentCBOR":"<canonical hex>","signerPublicKey":"<this phone key>","credentialID":0}
```

Supported profiles are `kavach-cose-spend-v1`, `kavach-raw-spend-v1` (CLI only), `kavach-cose-genesis-v1`, and `kavach-cose-policy-v1`. The device key
must match exactly; credential IDs are 0–15. The companion independently decodes the
actual CBOR. Request IDs and desktop labels are not ledger authorization domains.

## Kavach subset

The canonical `serialiseData` subset uses shortest integer/byte encodings, empty
definite lists, nonempty indefinite lists, and constructor tags 121–127/1280–1281.
Only unsigned integers and byte strings up to 64 bytes are decoded; intent size is
at most 1536 bytes, depth at most 16, at most 256 nodes. Alternate CBOR representations,
trailing bytes and unsupported fields/shapes fail closed.

The signed digest is **BLAKE2b-256 over the exact canonical intent CBOR**. A supported
intent contains `KAVACH_INTENT` + byte 1, protocol version 1, network 0/magic 42,
deployment identity, account NFT policy with empty name, core bindings, state version,
state reference, finite validity, and an ordinary Spend. The parser accepts exactly
one positive ADA allocation to a key-payment enterprise address or key/key base address.
Other assets, script credentials, pointers and other actions are rejected. Inputs
must be unique, ordered and distinct from the state reference. Max account fee is
5,000,000 lovelace; validity is at most 300,000 milliseconds.

Review derives the full recipient Bech32 address and amount from those bytes. The
phone **does not resolve the state NFT or inputs against a node**. The integrating
wallet remains responsible for state authentication, enrollment and actual role
membership. The phone makes this limitation visible. All signed identity/core/input
fields can be inspected before approval.

## Signatures and response

Raw profile signs the 32-byte intent digest directly (Kavach scheme 0).

COSE uses an enterprise network-0 address `0x60 || BLAKE2b-224(phonePublicKey)` and
protected map `{1:-8,"address":addressBytes}` with definite, minimally encoded CBOR
in that order. Signed bytes are `['Signature1', protectedBytes, h'', digest]`. Returned
COSE_Sign1 is `[protectedBytes, {}, digest, signature]` and COSE_Key is
`{1:1,3:-8,-1:6,-2:publicKey}`. No unsigned payload summary controls what is signed.

Response fields are `version:1`, `kind:"approval"`, `requestID`, `profile`,
`credentialID`, `publicKey`, `digest`, `signature`, and optional `key`.
For COSE, `signature` and `key` are full hex COSE structures. For raw, `signature` is
the raw 64-byte hex signature and `key` is absent. Integrators must verify all bindings,
not trust a matching request ID. The CLI verifies the evidence against the original
trusted request file. Outputs are not proof of transaction submission or settlement.

## Future Yano adapter

Yano may use the same pairing identity and authenticated envelope. Ordinary Cardano
transaction approval needs a separate versioned profile and an independently reviewed
transaction decoder on the phone. It must expose required signers, resolved inputs,
collateral/returns, fees, outputs, withdrawals, certificates, minting and script effects
or reject unsupported operations. Never reinterpret a Yano transaction as a Kavach
intent or feed an opaque hash into the existing approval screen.

## Compact QR transport

QR strings may use `YANO1:` followed by base64 of Apple's Compression framework
`COMPRESSION_ZLIB` (raw DEFLATE) encoding of the original UTF-8 envelope. The signed
body bytes are unchanged. Decoding uses a fixed 8193-byte output buffer and rejects
zero-length, malformed or more-than-8192-byte expansions before parsing JSON. Plain
JSON remains accepted. `companion-exchange qr REQUEST_JSON` emits this compact form.


## Genesis enrollment profile

`kavach-cose-genesis-v1` bodies require empty `intentCBOR`, a canonical `stateCBOR`
and a signed `expiresAt` Unix millisecond timestamp no later than five minutes ahead.
The phone reconstructs Kavach's existing genesis possession preimage from deployment,
account, core, module and BLAKE2b-256 of the configuration. Its proof digest is the hash
of that canonical preimage, not the hash of the whole state. The phone validates zero
initial counters/Normal state, key ordering/uniqueness, its own credential membership,
all six thresholds and defensive-role independence. Recovery timings are structurally
bounded but **not committed by this genesis proof**; the review explains this.
It displays all keys/policies and script identities and signs only after user presence.
Backend/ledger validation still checks complete protocol state and key validity.

Spend bodies may also carry signed `expiresAt`; the earlier of this timestamp and
intent expiry applies. A genesis transport refresh reuses the plan's request UUID and
proof digest so an existing Activity response can be returned without signing again.
The backend verifies its current export expiry as well as exact request/profile/key/
credential/digest matching before cryptographic verification. Requests from lost or
restarted backend plans cannot import. Existing account state is unaffected by pairing.

## Configuration and module replacement

`kavach-cose-policy-v1` carries both `intentCBOR` and the old `stateCBOR`, plus
`proofPurpose` (`operation`, `possession`, or `candidate`) and transport `expiresAt`.
The existing signed-envelope and compressed `YANO1:` transport bounds apply. The phone
checks matching account/deployment/core/version domains, normal state, a finite interval
of at most five minutes, and only ReplaceConfig or ReplaceModule. It reconstructs both
configurations, validates role separation, key uniqueness, fixed COSE/witness methods,
small/strong approval implication, and optional daily/weekly budget details.

`operation` signs the ordinary intent digest and requires the phone's old admin membership.
`possession` (ReplaceConfig) and `candidate` (ReplaceModule) sign BLAKE2b-256 of canonical
Constr 0 containing `KAVACH_CONFIG_POSSESSION_V1` bytes, intent digest bytes, destination
module reference, and the digest of the complete target configuration. The phone key must
be the requested credential in that destination. Mixed credentials must be configured COSE.

The review shows old/new keys and roles, method assignments, tier amounts, counter identity,
period and budget limit, and distinguishes administrative approval from key possession.
Changing periods resets on the next spend; disabling retains the counter. These proofs
remain subject to old-admin and ledger validation. The phone does not independently query
the live state NFT or establish deployment trust from a module hash alone. Recovery and
ordinary Yano transaction-signing profiles remain unsupported. Imported oversized requests
fail closed and must not be signed as opaque digests.


Mixed genesis uses the existing `kavach-cose-genesis-v1` envelope and genesis possession
domain. The configuration may be legacy eight-field roles or the five-field mixed profile;
periodic wrappers are not accepted at genesis. The phone must occur in the mixed COSE list.
The review displays every credential's method. Kavach's restricted setup module commits the
final policy module and requires a separate explicitly approved activation; the genesis
proof is not a reusable administrative or activation proof.
