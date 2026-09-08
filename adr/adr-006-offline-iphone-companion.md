# ADR-006: Offline iPhone companion approval transport

- Status: Proposed; bounded development implementation, physical live-ledger qualification pending
- Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md), [ADR-005](adr-005-browser-wallet-authentication-and-demo.md)
- Scope: Local dashboard and native iPhone transport; no contract or wire-schema changes

The dashboard may collect existing mode-2 COSE evidence from an independently keyed
phone as well as a CIP-30 wallet. This does not introduce mixed signing modules or
change existing account addresses/configurations. The fee/collateral wallet still
supplies transaction witnesses. Pairing does not enroll a spending authority.

The first live route is a new COSE account. Its phone key is included in the proposed
configuration, and the phone reviews all registered public keys, all six authority
policies, deployment/account/core/module identities before signing the existing
`ProofDomains.genesis` digest. It reconstructs the configuration digest and canonical
proof preimage from the complete state datum. It rejects non-genesis counters, unsupported
networks/schemas, malformed policies and defensive-role overlap. The genesis proof
binds configuration and script identities; it does **not** bind the recovery timing
fields. The phone explicitly explains that limitation. Complete initial-state acceptance
remains the backend and immutable validator's responsibility.

Ordinary single-recipient ADA Spend uses the existing canonical intent digest. Other
actions, native assets and unsupported recipient forms are not exported as phone
profiles. No opaque-digest approval or automatic fallback is allowed. The phone does
not independently resolve on-chain NFT ownership or current state.

The backend owns a separate persistent Ed25519 **request authentication** key, created
in an ignored local file with permissions 0600. It never owns the phone or wallet's
spending keys. Pairing pins that request key only after full fingerprint comparison.
Signed envelopes authenticate exact body bytes under `YANO_COMPANION_REQUEST_V1` plus
NUL. Compact QR encoding uses raw DEFLATE with bounded decompression, not encryption.
Keep exchanged data private; there is no relay, push channel or networking on the phone.

Exports are limited to existing pending plan credentials and supported proof domains.
Imports bind request ID, profile, credential ID, public key and expected digest, then
use the existing strict COSE parser and signature verifier. Wrong-plan, altered, stale
or duplicate collected proofs reject. Export expiry is at most five minutes and never
extends a Spend intent's ledger validity. Genesis refresh retains its per-plan ticket
so an already-approved proof can be returned from phone Activity after refreshing its
transport window; a backend restart loses the plan and invalidates its ticket.

Private spending keys remain protected by the existing iPhone Keychain/user-presence
flow. This is not Secure Enclave Ed25519 signing and is not production-qualified.
Replacing/rotating keys, recovery, transaction-witness accounts, ordinary Yano transaction
signing and heterogeneous module composition require separate work.

Validation and usage are recorded in the [demo guide](../demo/README.md) and
[browser acceptance ledger](../docs/browser/completion-checklist.md). Do not equate
synthetic CCL signatures with physical iPhone ledger acceptance.

The dashboard can also scan the phone's existing plain-JSON approval QR through an
explicit camera action. Frames remain local; only decoded approval JSON reaches the
existing local backend endpoint. Scanning uses the same credential/digest checks and
backend cryptographic verification as paste. Camera ownership is bounded and released
on read, close, tab hiding and timeout, including late permission resolution. No new
signature domain or automatic ledger submission is introduced.

A subsequent recipient/fee-payer collision in the demo builder was diagnosed independently
of QR decoding. The fix preserves signed allocation outputs and adds explicit separate
fee funding, without changing the protocol. See the [diagnosis](../docs/browser/evidence/companion/sponsor-recipient-rejection.md).
