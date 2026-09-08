# Phase 1 implementation review

Status: Phase 1 implementation self-review complete (2026-09-07).
This is not independent audit approval or production deployment approval.

| Boundary | Enforcement and evidence |
| --- | --- |
| Identity and genesis | One-shot creator seed, exact NFT mint quantity/name and canonical state custody output; required genesis module invocation and all-key possession. Creation and invalid configuration tests cover key aliases, malformed records and defensive role overlap. |
| Canonical signatures | Explicit encoder matches all nine frozen CBOR/digest/rendering vectors. Malformed intent records are signed again for shape tests, so their rejection does not depend on an unrelated invalid signature. Raw Ed25519 is separate from transaction-body witnesses and CIP-8. |
| Immutable accounting | Core requires the exact signed anchor at the authenticated full account address. Every account input binds its digest to that core. The anchor checks the complete input set, recipients, account change and net fee. Module approval cannot replace it. Key/foreign anchors, missing checkpoints and native diversion reject. |
| State configuration | Current state is authenticated using full NFT identity and custody. Full registry and role validation occurs at genesis; ordinary spends use narrower checks only after state authentication. The state validator cannot spend at all in Phase 1. Future installation/state-transition code must re-establish the full invariants. |
| Value and numeric bounds | Native accounting checks individual and aggregate account input quantities, complete native maps and bounded ADA debit. Eight bounded inputs cannot overflow native union's signed 128-bit intermediate range. Compiled quantity/fee boundary tests and 64 generated allocations cover loss/creation and multiple change outputs. Recipient masks derive only from signed bounded indices; tests cross byte boundaries. ADA-only shortcuts inspect both complete maps. Aggregate checks reuse validated keys and require positive ADA, preserving the eleven-native-asset union bound. Whole transfers preserve maps without partial-profile aggregate arithmetic. |
| Replay and lifecycle | Exact signed consumed inputs and current state reference/version are required. Normal mode is mandatory. Real node resubmission fails after consumption. Administration and recovery variants fail closed in Phase 1. |
| Withdrawal purposes and receipts | Distinct registration ABI cannot authorize spending or deregistration. Core/module rewarding invocations bind the same intent and receipt table. Positive receipts have distinct indices, full immutable sinks and no participation in account conservation. Compiled receipt tests and the complete actual-reward ledger gate pass. Actual 1,000 ADA withdrawals reached separate shared-sink outputs with 2 ADA top-ups; node rejection covered underpayment and recipient substitution. |
| Client preparation | SDK checks supplied ledger state, exact inputs and spend proofs before attaching witnesses. Network-bearing custody/input addresses must match locally derived addresses. The final transaction still requires ledger validation. Backend objects and client rendering are not substitutes for trusted state resolution. |
| Budget and artifact handling | Full redeemer estimates are required; missing, duplicated, malformed or over-budget results reject. Fees/collateral are balanced after adding an explicit allowance. Reference-backed witness removal occurs before balancing. A fresh isolated build reproduced all fourteen Phase 0 and five final Phase 1 templates byte-for-byte. All 322 local tests and four short full-account DevKit profiles pass. |

A negative ledger test initially failed to establish script rejection after increasing
its fee: its collateral reservation still reflected the old fee. Updating the collateral
reservation and return consistently allowed the token-diversion tests to reach the
intended script failure, followed by confirmed authorized transfers. The tests distinguish
node script rejection from generic transaction rejection.

The qualified paths use the unmodified output of the pinned compiler and actual PV11
node validation. Native Value operations are experimental upstream features, so a new
compiler, ledger version or cost model requires requalification. Separate component and
constructed-context tests do not prove arbitrary ledger composition or all combinations
of numeric/byte bounds. The explicit combined scenarios include the whole-transaction
budget allowance; clients must still evaluate their actual transaction.

Residual Phase 1 limitations remain explicit: sealed state cannot be closed or upgraded;
state deposits and reference-script outputs are permanently retained at that development
script. External key-locked collateral and fees are required. No recovery, account delegation or voting,
dApp interaction, CIP-113 adapter, CIP-8 or hardware-wallet integration is
implemented. Production use remains subject to later phases and independent review.

## Acceptance audit against ADR-001

The following maps section 29's Phase 1 requirements and section 16.1's additional
reward gate to actual checks. Later administration/recovery, rollback integration and
independent audit remain Phase 2/3 work; their absence is not hidden by transfer tests.

| Required behavior | Current evidence | Disposition |
| --- | --- | --- |
| Creation and first module | `AccountCreationTest` evaluates emitted NFT/module scripts, including possession and invalid configurations; four `AccountDevkitTest` profiles confirm creation. | Passed for the Phase 1 sealed-state implementation. |
| Canonical Java encoding | `AccountCodecConformanceTest` compares all nine baseline action vectors, digests and rendering; `verifyProtocolBaseline` validates frozen files. | Passed; non-transfer action encoding does not imply those actions are executable. |
| Exact-input spends and complete asset accounting | `AccountTransferTest`, `AccountWholeTransferTest`, `AccountValueBoundaryTest` and `AccountConservationPropertyTest`; actual ADA, eight-input native, eight-recipient native and 140-token whole transfers. | Passed in the documented bounded profiles. |
| Malformed data, identity, checkpoints, domain, replay, input/output reuse and fee attacks | `AccountAdversarialTest`, creation/receipt/value tests evaluate compiled UPLC; live tests distinguish script rejection from generic ledger failure for recipient substitution and native diversion, and reject replay. | Passed with component versus ledger scope explicit. |
| Positive rewards through a complete authorized spend | `AccountReceiptsTest` composes full current validators, including distinct shared-sink top-ups, reuse/underpayment/overlap rejection. The live reuse worker received actual refunds to the unchanged core/module credentials and confirmed a spend from the fresh final-script account. | Passed: epoch 102 transaction confirmed; both balances drained, underpaid receipt rejected by node script validation. |
| Execution limits, Java documentation and artifacts | Four combined-budget scenarios include 5% allowance; `check` validates Javadocs and toolchain; fresh isolated compilation reproduces all 19 templates. Current manifests and 322 local / 12 short ledger test results are archived. | Passed for the pinned compiler and DevKit parameters; no exhaustive cost or production claim. |

The previous reward worker targeted an earlier asset template. It was intentionally
terminated only after the replacement account was created and funded, so it cannot race
for the shared pending refunds. Public setup verification established the same deployment
domain, core/module hashes and fixed sinks. This reuses pending proposals; it does not
recover the previous account's keys or authorize any change to its state.
