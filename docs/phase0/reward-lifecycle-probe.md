# Phase 0: reward receipts and registration lifecycle

This is a historical experiment report. See the [qualification report](qualification.md) and [acceptance ledger](completion-checklist.md) for the current Phase 0 status.

Status: partial feasibility evidence, 2026-09-07. This probe does not complete Phase 0, authorize account operations, or establish live positive-reward handling. It uses the existing pinned JuLC snapshot, CCL `0.8.0-pre5`, Java 25 and Plutus V3 / protocol version 11.

## Contract boundary

`RewardLifecycleProbe` is an explicitly dispatched multi-purpose script. It has immutable parameters in this order: an authority payment key hash, a full ledger key address for reward receipts, and a positive registration deposit. Parameter validity is a deployment responsibility; the experimental fixture supplies a 28-byte key hash and the actual network deposit.

The certifying branch accepts only deposit-bearing stake registration with the configured deposit and a script credential. It requires integer redeemer `-1`, but no authority signature. The ledger associates that certifying script witness with the certificate's credential; accepting registration confers no spending or administrative power. Deregistration, delegation, combined registration/delegation and unrelated certificates reject. The compiled wrapper rejects spending, minting, voting and proposing purposes. Legacy witness-free registration need not invoke this branch.

The rewarding branch requires the authority in transaction required signatories and the script's credential in withdrawals. A zero withdrawal uses redeemer `-1` with no receipt. A positive withdrawal uses a nonnegative integer redeemer identifying one output; it must have the complete immutable sink address, ADA only, at least the withdrawn amount, no datum, and no reference script. An external minimum-ADA top-up is allowed. Negative amounts, missing authority, missing withdrawal, invalid indices, underpayment and altered destinations reject. Scripts cannot read the current reward balance from the Plutus context; the ledger must independently validate the requested amount.

**This experiment allows exactly one withdrawal per transaction.** That restriction prevents multi-withdrawal receipt reuse by rejecting composition entirely. It is not the production checkpoint/module ABI, which needs several withdrawals and distinct receipt allocations. It has no account inputs, state, intent digest, replay resource or account-value accounting. Repeated authority-approved withdrawals are allowed. It must not be used for custody.

## Tests and actual evidence

`RewardLifecycleProbeTest` has 29 cases and executes compiler output directly at PV11. It covers positive/full receipts, external top-ups, zero withdrawals, permissionless explicit registration, malformed receipt destinations/values/datums, very large/negative indices, multiple withdrawals, unsupported certificates and all other script purposes. Negative cases must terminate with a script failure, not budget exhaustion. Each evaluation is capped at 500,000,000 CPU / 2,000,000 memory; these fixtures are not worst-case production bounds.

Measured successful receipt fixture: 67,885,640 CPU / 231,137 memory. The live explicit registration used 8,309,063 CPU / 33,336 memory; zero withdrawal used 19,445,237 CPU / 63,836 memory. See the [experimental scalar schema/vectors](../../conformance/phase0/reward-lifecycle-v0.cddl).

`RewardLifecycleProbeDevkitTest` creates fresh local-devnet sponsor and authority keys in memory. The sponsor funds fees, collateral and the registration deposit. The authority does not sign registration. The test requires:

- Explicit-deposit registration to confirm with a certifying script witness.
- Removal of that script witness to cause a ledger missing-script rejection.
- A separately submitted, authority-approved zero withdrawal to confirm.
- Deregistration to fail on the node with `ValidationTagMismatch` and an explicit Plutus error, with `isValid=true` so the negative transaction is rejected rather than consuming collateral.
- Duplicate legacy registration to fail with a ledger already-registered error.

[Public evidence](evidence/reward-lifecycle-2026-09-07.json) records source/toolchain hashes, script bytes/hash, authority hash, protocol parameters, transaction IDs, execution units and rejection responses. No private keys or seed phrases are recorded. Successful registration leaves a nonrefundable devnet deposit under this script profile; the test never resets the cluster.

## Implementation findings

JuLC's annotation-processor blueprint generation does not support manual multi-purpose dispatch. Explicit `@Entrypoint(purpose = ...)` handlers compile with the normal blueprint generator. The tests exercise the emitted wrapper as well as branch behavior.

CCL's high-level stake registration builds a legacy certificate. The harness replaces it with `RegCert` before balancing and attaches the corresponding certifying redeemer. Simply adding that redeemer through `preBalanceTx` did not enable CCL's automatic collateral selection: the first transaction was rejected by the node with `NoCollateralInputs`. A small test-only `Tx` subclass explicitly advertises script intents, enabling normal balancing, evaluation and collateral selection. This is a harness adapter, not an upstream CCL fix or a production registration API. The final test asserts collateral presence and actual node confirmation. JuLC's full transaction evaluator estimates successful script costs; the node independently validates transactions.

## Gates still open

Positive reward receipts are validated in compiled synthetic contexts only. A real positive reward balance must still be credited through a ledger-valid fixture, followed by successful full withdrawal and rejected zero/partial withdrawal attempts. The existing faucet funds payment UTxOs, not reward balances. This test does not modify genesis, reset the user's cluster, or submit governance changes to simulate success.

Also pending: multi-script receipts at a shared sink, disjointness from account allocations, positive-reward spending/recovery, register-and-withdraw ordering in one transaction, permissionless script re-registration in a controlled fixture that permits deregistration, production ABI/numeric limits and worst-case budgets. The observed registration-then-withdraw success concerns two confirmed transactions only. Legacy registration is covered by the earlier withdrawal probe; it must not be generalized to deposit-bearing registrations.
