# ADR-005: Browser wallet authentication and management demo

- Status: Proposed; development implementation available, compatibility qualification incomplete
- Priority: Safety and correctness before convenience or fee reduction
- Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md), [ADR-004](adr-004-phase2-immutable-state-transition-anchor.md)
- Scope: Explicit transaction-witness and CIP-8 authentication profiles; a browser management application

## Decision

Add browser-wallet authentication as a separately qualified increment. Preserve the raw
Ed25519 Phase 2 baseline, its evidence and running real-delay worker. The existing module
must not gain an implicit alternative authorization path. Each additional profile has an
explicit module identity, configuration/proof specification, role rules and qualification.
No private credential export is permitted. The first implementation enrolls raw payment public keys; a verified public-key export or independently trusted public key is needed.

Support two distinct modes:

| Mode | Wallet operation | Authorization evidence |
| --- | --- | --- |
| Transaction-witness module | CIP-30 `signTx` | Registered payment-key hashes in ledger-verified required signers |
| COSE-aware module | CIP-30 `signData` | Ed25519 signature over the exact CIP-8 COSE signing structure containing the expected Kavach payload |

Either mode may also use CIP-30 `signTx` to authorize the connected wallet's fee/collateral
inputs. Fee sponsorship alone must never count as account authority unless that key is
explicitly enrolled in the relevant account policy. Modes must not be combined with an
implicit OR, downgraded after a signing error, or inferred from wallet branding.

## Transaction-witness profile

The module checks the applicable role threshold against `TxInfo.signatories`, derived from
the transaction body's required signers. Merely attaching a vkey witness does not establish
script-visible authority. The ledger checks the corresponding payment-key witnesses. Stake
keys and script credentials are not interchangeable with payment-key required signers.

The immutable validators retain every intent, state, value, mode, timing and replay check.
The signed transaction commits to its script data, including the canonical intent redeemers.
It is transaction authorization rather than a detached intent signature. Changing fee,
inputs, outputs, validity bounds, script data or required signers after signing requires
rebuilding and collecting signatures again. All threshold participants sign the same final
body; the coordinator merges verified witnesses without changing that body.

All lifecycle roles and independent defensive policies remain mandatory. The profile must
specify and test all-key enrollment at genesis and candidate installation, possession of
introduced keys during rotation, and target-key authority at recovery completion. An old
admin's transaction witness does not establish possession by a newly introduced key.
Recovery completion requires the appropriate new target keys, never a lost old spend key.
Preserve registry/policy/transaction bounds; measure large witness sets against actual limits.

## COSE profile

The payload must be derived deterministically from the canonical intent, or the existing
separate genesis/configuration/recovery-target proof domain. The verifier reconstructs the
COSE `Sig_structure` from the exact protected-header bytes and expected payload; stripping
a COSE wrapper does not yield a raw Kavach signature. The first profile targets CIP-30's
unhashed payload and empty external AAD. Other CIP-8 variants require explicit qualification.

Define a bounded supported header/address profile and a normative proof encoding before
contract implementation. Verify Ed25519 algorithm and curve, signature/key lengths, protected
address-to-key binding, deployment/network compatibility and exact payload. Reject duplicate
or ambiguous headers, unsupported critical fields, trailing bytes, unrecognized variants,
extra evidence and cross-domain replay. Do not canonicalize signed protected bytes before
verification. Parsing by the backend alone cannot establish any on-chain security property.

Prefer bounded on-chain reconstruction over an unrestricted CBOR parser where the supported
wallet profile permits it. Which header variants are accepted must be based on captured
real-wallet outputs, not assumptions. Display the decoded intent and matching digest in
the application; a wallet displaying only a digest cannot independently verify the human
meaning presented by a compromised application. Document this trust boundary explicitly.

### Candidate 2: address-valued key identifier

Yano's signer delegates to CCL `CIP30DataSigner` (the inspected Yano checkout pins
`0.8.0-pre4`; Kavach reproduces this behavior with its required `0.8.0-pre5`), which includes the
CIP-30-permitted optional `kid` in both COSE_Key and protected headers. The first
Kavach adapter rejected that valid variant. Accept two explicitly bounded protected maps,
with the [normative profile](../protocol/browser/specification.md) defining a one-byte
proof suffix for the second. The on-chain verifier reconstructs the original signed map,
including kid equal to the signing address; it never strips or normalizes signed fields.
The SDK requires matching key/signature identifiers and retains payload, network, key,
algorithm, curve, length and duplicate checks. Yano does not need to drop a valid field.

This changes the browser module template and its applied hashes (including mode 1,
which shares the template). Already installed modules retain their original behavior.
Existing accounts need an authorized module replacement to use the added COSE variant;
a backend restart alone cannot upgrade them. No immutable core or scheme-zero change is
required. CCL-generated regression fixtures establish library-format compatibility, not
an actual wallet-extension/hardware end-to-end qualification.

## Existing-account compatibility gate

The current immutable code treats configuration as bounded opaque Data in important places,
but also projects typed `ModuleRedeemer` and genesis proof fields. The CDDL, SDK renderer,
state decoder and administration proof checker currently specify the first raw-Ed25519
profile. Module replaceability alone does not establish arbitrary proof-format compatibility.

Before promising preservation of existing account addresses/hashes, exercise creation,
transfer, old-module-to-candidate replacement, configuration rotation and recovery with each
new profile against the exact existing immutable artifacts. Update module-specific decoding,
rendering and SDK dispatch without silently changing scheme 0 or archived conformance vectors.
Specify separate versioned profiles. If the required proof representation cannot satisfy
existing immutable bindings, record an explicit ABI/core revision and fresh deployment; do
not bypass checks or claim an in-place upgrade. This gate also covers all-key possession at
creation/replacement, not merely one successful transfer.

## Web management demo

Use a React/TypeScript frontend with Cardano Foundation's Connect with Wallet integration,
and a Java/Gradle backend using the existing Kavach SDK and CCL `0.8.0-pre5`. Pin the selected
frontend/compiler/connector versions when implementing. CF Connect handles discovery and
connection; the application still owns exact payload selection, CIP-30 calls, capability
checks, witness verification and transaction review. Do not use a generic login message as
account spending authority.

The first complete vertical slice is connect, enroll roles, create, export locator, fund and
transfer using the transaction-witness profile. Then expose configuration/module replacement,
freeze/unfreeze, recovery start/cancel/complete and locator restoration, followed by COSE mode.
The application should show account and connected-signer addresses separately, current mode,
role thresholds and approvals, pending recovery target/deadline, fee/deposit breakdown, and
confirmed transaction status. It must refuse unsupported actions and make pending operations
visible. A complete recovery demo respects the actual minimum delay; a pre-aged disposable
pending account can demonstrate completion without changing protocol timing.

The backend authenticates current state, constructs canonical requests, evaluates with the
node and returns unsigned transactions. Signing keys remain in wallets. The frontend derives
its review from the exact typed request/body to be signed, checks returned signatures and
body identity, and submits only the reviewed transaction. Detached COSE approvals can be
collected before fee balancing if the canonical signed intent remains unchanged. Transaction
witnesses are collected only after the full body and budgets are finalized.

CF Connect does not make a browser wallet use a local DevKit automatically. For real browser
tests, qualify a wallet with explicit custom-network/provider support, or deploy separately
to a supported public testnet. CIP-30 network ID 0 alone cannot distinguish DevKit, preview
and preprod. Require explicit deployment identity and backend/network agreement. A mock
CIP-30 provider is useful for automated UI tests but must be labeled and cannot establish
real-wallet compatibility. Never import real wallet secrets into the demo backend.

## Acceptance and sequence

1. Complete outstanding Phase 2 qualification without restarting its recovery worker.
2. Specify and probe transaction-witness profile, immutable compatibility and whole lifecycle.
3. Demonstrate create and transfer end-to-end through an actual browser wallet and CF Connect.
4. Extend the UI to administration/recovery and multi-party approval collection.
5. Specify/probe COSE, capture real wallet vectors, and qualify the same lifecycle and UI.

Tests must cover correct/wrong/insufficient required signers, a sponsor mistaken for owner,
missing witnesses, cross-account/state/deployment replay, body mutation after signing,
new-key enrollment and recovery target substitution; COSE additionally needs header,
address/key, payload and signature substitution and malformed/oversized input coverage.
Keep compiled adversarial tests, complete ledger tests, multi-signer browser tests, maximum
budget/transaction-size measurements and paid fees separate. Wallet and hardware support
claims must name tested versions and signing operations. No production readiness follows
from a functioning demo.

## Implementation evidence

The [dashboard app](../dashboard-app/README.md) and [bounded module profile](../protocol/browser/specification.md)
are implemented as development candidates. Both modes have passed compiled checks and full
DevKit short lifecycle tests, including cross-mode installation through the demo API. The
existing immutable validator sources and raw scheme-zero encoding are retained. This does
not establish compatibility with every historical deployment hash.

The [acceptance ledger](../docs/browser/completion-checklist.md) remains authoritative for
unfinished work. Real extension captures, full maximum-shape qualification, positive-reward
UI allocation and an independently decoded frontend transaction review remain outstanding.
The initial UI's canonical rendering comes from the Java SDK; the backend is therefore part
of the trusted review client. Actual-delay recovery is not replaced with a shortened demo.

## References

- [CIP-30](https://cips.cardano.org/cip/CIP-0030): separate `signTx` and `signData` interfaces.
- [CIP-8](https://cips.cardano.org/cip/CIP-0008): signed COSE structure and protected headers.
- [CF Connect with Wallet](https://cardano-foundation.github.io/cardano-connect-with-wallet/): React wallet connection and signing integration.
