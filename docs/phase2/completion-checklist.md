# Phase 2 administration and recovery acceptance

Status: implementation in progress. Phase 0, Phase 1 and fee-optimization evidence
remain historical qualifications of their archived artifacts. No production approval.

The authority is ADR-001 sections 23–26 and 29, the ordinary V1 CDDL and module ABI.
All seven state mutations must be implemented; transfers remain Normal-only.

| Requirement | Acceptance evidence required | Status |
| --- | --- | --- |
| State custody | Consume authenticated current NFT; unique successor; no burn, identity/core/timing drift, ADA reduction or foreign script input | Compiled composition and short ledger cases pass; final review pending |
| Configuration replacement | Old admin, full new config, possession of every newly introduced key; old proofs cannot authorize a different target | Compiled adversarial cases and live all-key replacement plus transfer pass; final review pending |
| Module replacement | Separate old admin and candidate validation, exact ABI/envelope/receipts, no self-approval or same-credential double invocation | Compiled adversarial cases and live candidate replacement plus transfer pass; final review pending |
| Freeze / unfreeze | Exact mode transitions, independent roles, stale state rejection, both freeze/spend ordering outcomes | Pending |
| Recovery start / cancel / complete | Exact target retention, checked version/sequence, conservative delay, monotonic cooldown, independent cancellation; no old spend key at completion | Pending |
| Adversarial and model tests | Compiled full composition, malformed types/arity, role/key duplicates, target substitution, replay, overflow and all mode/action pairs | Pending |
| Clean-device restoration | Locator-only discovery through replacement provider, authenticated current state, surviving authorities and new collateral provider | Locator and defensive unfreeze pass on DevKit; delayed target completion running |
| Live ledger qualification | All transitions and subsequent transfer; actual positive rewards and disjoint receipts through recovery, including invalid receipt rejection | Pending |
| Economics and limits | Combined CPU/memory, transaction/script bytes, min-ADA and paid fee breakdown; transfer regression | Pending |
| Review and reproducibility | Javadocs, self-review disposition, source/artifact hashes, fresh compile and preserved schema fixtures | Pending |

Live recovery must respect the specified minimum 86,400,000ms delay. Constructed
contexts establish boundary behavior, not elapsed-time ledger acceptance. DevKit must
not be reset or have time/parameters altered to accelerate qualification. Persist public
pending manifests and resumable devnet transaction artifacts as appropriate, never keys.

The implementation starts by separating immutable transition computation from
transaction custody and module authorization, then tests their complete composition.
A helper's success alone is not state-transition acceptance.

The current evidence is recorded in [progress.md](progress.md) and the
[locator specification](account-locator.md). The actual-delay worker confirmed initiation
`a580c1e852f9649cc7c71cc26b35fdfb8e756266c8d331d1a10083700ce6b9c1`;
its earliest completion ledger timestamp is `1788877344000` (2026-09-08 14:22:24 UTC).
This is pending evidence, not successful completion. Keep the worker alive and preserve
`build/phase2/recovery/delayed-recovery.json`.
