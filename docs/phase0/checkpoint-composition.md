# Checkpoint composition experiments — 2026-09-07

This is a historical experiment report. See the [qualification report](qualification.md) and [acceptance ledger](completion-checklist.md) for the current Phase 0 status.

These experiments use normal compiled JuLC output, the pinned source-fixed toolchain and CCL `0.8.0-pre5`. They are disposable Phase 0 validators, not a Kavach account or a production module. All keys used on DevKit are generated for the test and stay in memory.

## Paired authorization

`BindingCheckpointProbe` is applied first as a signature module and then as a core checkpoint parameterized by that module hash. Each invocation independently authenticates the genesis state NFT reference, enterprise holder address and exact three-field probe datum. Both bind account policy, deployment domain, genesis version, operation, consumed input and both reward script hashes. The core requires the exact module Rewarding redeemer; the module verifies the raw Ed25519 intent signature. A reference-script attachment or Certifying redeemer is insufficient.

The [live evidence](evidence/binding-2026-09-07.json) records successful paired withdrawal transaction `e0a836022535cc713f99bda6c15222ecda47aa2ad5c6862b61596ff4262ab72e`. Invalid module signatures and mismatched module/core authorizations fail phase 2 at the node. The successful full-witness transaction is 10,746 bytes. This is a small genesis challenge, not a maximum production intent.

Compiled negative tests cover foreign domains/accounts/versions/operations/inputs/hashes, malformed constructors and extra fields, different module redeemers, wrong purposes, invalid signatures, duplicated state references, shared-sink receipt reuse and insufficient receipts. Positive receipt contexts require distinct indices and permit external minimum-ADA top-ups. Such contexts establish script behavior, not reward-credit ledger acceptance.

The paired probe intentionally duplicates the same authorization in both redeemers. The V1 candidate ABI specifies separate core/module record shapes and role-dependent configuration evidence. It still requires equality of the canonical envelope and receipt table. Phase 1/2 must test the actual compiled implementation of that ABI; probe hashes are not production core hashes.

## Registration and ordering

The [controlled fixture](evidence/registration-ordering-2026-09-07.json) registers a disposable credential with the legacy registration certificate, authorizes deregistration using its dedicated key, and confirms legacy re-registration without a script witness or that authority as a required signer. This fixture deliberately permits deregistration; production checkpoint/module scripts must reject it. Re-registration transaction: `02890faaae53f6cdf0b4532785bce89249c60364cc99b3adc7da7bf0c48e9135`.

Legacy registration and a first withdrawal in the same transaction reject with `ConwayWithdrawalsMissingAccounts` on the recorded node/protocol version. Register and confirm before withdrawing. This does not imply all certificate forms are witness-free: the separate reward lifecycle experiment confirms deposit-bearing registration with a Certifying witness and rejects missing witnesses, duplicate registration and deregistration.

## Reference-script availability

The [availability experiment](evidence/reference-availability-2026-09-07.json) publishes two identical reference scripts under the compiled always-fails holder, then confirms a withdrawal with each copy and a third with a normal script witness and no reference input. All three use the same script hash. The first two transaction witness sets contain no duplicate Plutus script.

The test simulates unavailability by selecting the alternate copy or omitting references entirely. It does not spend an always-fails output. Locked reference outputs permanently consume disposable devnet minimum ADA. Production releases must retain exact script bytes, publish redundant copies and support discovery by hash. Full-witness fallback is conditional on transaction size; a large transaction must use another or newly published matching reference.

## Commands

`./gradlew test --tests '*BindingCheckpointProbeTest'` checks compiled binding/receipt behavior. `./gradlew integrationTest` includes the binding, registration-ordering and reference-availability tests alongside the original three live experiments. `./gradlew rewardCreditIntegrationTest` is separate because a real governance-deposit refund can take more than an hour; a synthetic positive withdrawal is not a substitute for that gate.
