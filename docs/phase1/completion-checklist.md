# Phase 1 acceptance ledger

Status: **complete for the Phase 1 development scope (2026-09-07)**. No production deployment or audit approval.

- [x] Typed wire adapters reproduce the V1 baseline; malformed data rejects on-chain.
- [x] Creator-bound one-shot creation invokes genesis configuration possession.
- [x] Asset validator requires immutable checkpoint and the exact intent digest.
- [x] Core authenticates referenced state, deployment, lifecycle and exact consumed inputs.
- [x] Partial spends enforce exact recipients, all-asset conservation and fee bounds.
- [x] Whole-UTxO transfers preserve large native-token maps.
- [x] First module validates configuration, independent roles and signature evidence.
- [x] Explicit registration and positive reward receipts work through complete operations.
- [x] Adversarial compiled UPLC tests, live DevKit tests and integrated budgets pass.
- [x] Review, Javadocs, reproducibility and limitations are recorded.

Phase 1 state custody rejects all mutations. Administration/recovery belong to Phase 2;
changing these development scripts changes their hashes. Use disposable local funds only.

## Current evidence (2026-09-07)

`./gradlew check phase1BuildManifest` passes: 322 local tests,
Javadoc validation, pinned toolchain acceptance and normative baseline verification.
All fourteen Phase 0 compiled templates remain identical to their recorded hashes.
The complete short `integrationTest` suite passes all 12 live tests.
The Phase 1 manifest records five templates and source digests. A fresh isolated compile
reproduced all nineteen Phase 0/1 templates byte-for-byte; the real positive-reward gate
and implementation self-review are complete. Current evidence files carry the `-current-2026-09-07` suffix.

- `AccountCodecConformanceTest` compares all nine typed action encodings with frozen
  CBOR, digests and renderings, plus state and genesis proof encoding.
- `AccountCreationTest` covers creator/seed/NFT failures, all-key possession, malformed
  configuration and independent roles. Sixteen-key genesis requires 9,723,539 combined
  memory units and 4,333,052,906 CPU steps in its compiled fixture.
- `AccountAdversarialTest` covers missing/wrong checkpoints, key-locked or foreign
  accounting anchors, state lookalikes, wrong domains, malformed signed records,
  output reuse, fee theft and signature evidence. `AccountValueBoundaryTest` covers
  partial quantities through 2^63 - 1, overflow rejection and exact fee bounds.
- `AccountDevkitTest` confirms ordinary ADA, eight-input partial-native, eight-input/eight-recipient native
  and 140-token whole transfers, with recipient substitution, native-token diversion and replay
  rejection. The native evidence is recorded under `evidence/`.
- `AccountBudgetTest` combines all eight asset invocations, core and module in four
  scenarios (zero/eight recipients and zero/positive rewards), including sixteen registry
  keys, eight members in each role and eight spend signatures. The positive-receipt,
  eight-recipient fixture uses 15,267,552 memory units and 8,377,552,336 CPU steps.
  The 5% per-redeemer allowance totals 16,030,934 memory and 8,796,429,958 CPU,
  below DevKit's 16.5 million / 10 billion limits. These are measured scenarios, not
  an exhaustive worst-case proof. The SDK rejects incomplete or over-budget estimates.
- Quantity boundaries include aggregate overflow, native-asset union limits, and
  recipient-mask positions 0, 7, 8 and 15. Sixty-four deterministic generated allocations
  validate complete conservation and reject one-unit change tampering.

`phase1RewardCreditIntegrationTest` is an explicit long-running task for actual
core/module proposal refunds followed by a complete account spend with shared-sink,
distinct receipts and top-ups. Its running process, rather than the pending manifest
alone, establishes that the refund wait is active. The manifest contains public data;
signing keys remain only in that process. The gate passed via `phase1RewardReuseIntegrationTest` with the final asset template.

The successful positive-reward run used `phase1RewardReuseIntegrationTest`: a fresh
account with the final asset script, authenticated against the unchanged checkpoint
hashes and immutable sinks from the original pending refund setup. The superseded
worker was stopped after the replacement was funded. No DevKit reset, parameter change,
or replacement proposal was used. Both refunds arrived in epoch 102 and the final authorized spend was confirmed.

## Final short-run fee observations

| Profile | Creation fee (lovelace) | Spend fee (lovelace) | Spend bytes |
| --- | ---: | ---: | ---: |
| ADA | 923741 | 1209667 | 1613 |
| Eight-input native | 923741 | 1684571 | 2953 |
| 140-token whole | 923741 | 1420719 | 6536 |
| Eight-input/eight-recipient native | 923741 | 2211522 | 4754 |

These are disposable DevKit measurements with a 5% execution allowance, not fixed
network quotes. They exclude reference publication fees, registration deposits and retained
state/reference output value. Parameterized module publication is close to the transaction
size limit; new configurations and toolchains require fresh evaluation.

## Real positive-reward acceptance

`phase1RewardReuseIntegrationTest` passed with the final scripts. Transaction
`827c211a4957e4319993577c8886bca500f62029aad4c60a358c3bfb422fbf11`
withdrew 1,000 ADA from each checkpoint and delivered distinct outputs 2 and 3 to the
same immutable sink, each holding 1,002 ADA including a sponsor-funded 2 ADA top-up.
Both reward balances then reached zero. Node script validation rejected recipient
substitution and an underpaid receipt; replay also rejected. The transfer fee was
1,321,924 lovelace and its size was 1,861 bytes. Public transaction outputs, withdrawals,
full test evidence and the validation summary are archived under `evidence/`.

The acceptance audit is in [review.md](review.md). Completion covers creation and
transfers with sealed development state; recovery, administration, production deployment
and independent security audit remain outside this phase.
