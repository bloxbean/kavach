# Phase 2 implementation progress

Status: in progress; initial publication/creation/transfer confirmed on DevKit. Administration/recovery ledger qualification remains pending. See the [acceptance ledger](completion-checklist.md).

The first implementation adds immutable successor calculation and consumed-state custody,
core binding to the mandatory state input, old-module role approval, configuration key
possession and recovery-target proofs. It preserves the normative state/action/ABI encoding.
The state validator replaces the Phase 1 sealed script and therefore changes deployment hashes.

Twenty focused compiled lifecycle cases pass: seven positive scenarios (configuration,
freeze, unfreeze, start from Normal/Frozen, cancel, complete), four wrong-role rejections
and nine forbidden-field/value/NFT rejections. The transfer and adversarial suites also
pass after sharing exact state authentication and module invocation bindings. These are
constructed contexts, not ledger acceptance; module replacement, exhaustive mode/action
coverage, boundary/property tests and the DevKit lifecycle remain pending.

The compiler requires explicitly typed BigInteger locals for the switch-derived interval
endpoints; the initial inferred locals caused double UnIData decoding in emitted UPLC.
Explicit typing fixed all seven positive lifecycle cases without changing time semantics.

Current template sizes under the previous fixed snapshot: state 8,095, core 10,954,
module 17,118 bytes. The module still exceeds the publication transaction limit before
parameters/overhead. Publication size must be resolved before live tests. Shared state
fields, singleton NFT authentication and invocation binding reduced the initial module
from 18,841 bytes; all required checks remain at their owning immutable/module boundary.

The user confirmed JuLC serialization PR #133 merged. A clean checkout of upstream main
at 6754861bc1e803d3f91b5e7fdfd1b7eccbec200a passed 1,485 compiler and 20 processor tests and was published locally as
`0.1.0-pre17-6754861-SNAPSHOT`.
The compiler source tree matches the earlier serialization-fix checkout; any size benefit
must be measured, not assumed. Toolchain provenance and binary pins are updated; Kavach `check` passed 350 local tests,
the positive toolchain gate, Javadocs and baseline verification.

Sharing the full six-role configuration validator with ordinary transfers reduced the
module to 16,320 bytes but raised maximum combined memory to 22.9–24.3 million units
including margin (limit 16.5 million). That experiment was rejected and reverted.
Transfer validation retains its authenticated-state spend-facing checks; all installation
and target paths establish full configuration validity. Publication size remains open.

## Linear policy validation and shared approval

The failed full-configuration-sharing experiment exposed repeated nested registry scans.
Policy membership now uses a linear subset check over strictly ordered IDs; role overlap
uses a linear intersection. Public-key uniqueness compares each pair once. All ordering,
unknown-member, duplicate-key, threshold and role-independence checks remain mandatory.
With those changes, full configuration validation on transfers passes the existing maximum
combined-budget cases, so the previous expensive implementation is superseded.

All 350 local tests, toolchain acceptance, Javadocs and baseline checks pass on upstream
6754861. Current module template is 16,258 bytes after sharing old-role approval with
spends. It still lacks space for parameters and publication overhead; it is not ready for
DevKit publication. The earlier 16,177-byte intermediate is recorded as a size comparison,
not a qualified deployment. More source factoring and actual transaction-size measurement
are required. No numeric profile bound was reduced to make these tests pass.

## Invocation shape regression checks

Sharing public-key membership between uniqueness and introduced-key possession reduced
that intermediate template to 16,210 bytes. Exact tag/arity checks for core, module and
genesis invocations then reduced it to **16,023 bytes**. ABI checks and all field validation
remain at their original boundaries, and cross-script comparisons still bind complete
invocations. The shape checks replace only reconstruction of the same outer record.

Five additional compiled test cases each cover trailing fields, missing fields, wrong tags
and unsupported ABI versions, with every copy changed consistently in the context. Valid
controls succeed and all twenty malformed cases reject across state/core mutation,
module mutation, module spend and genesis. `./gradlew check` passes 355 local tests,
toolchain acceptance, Javadocs and baseline verification. Applied parameters and publication
overhead still need space and actual DevKit qualification remains pending.

An earlier bit-mask experiment was reverted: passing a Data list to `writeBits` did not
produce its required native integer list in emitted UPLC. No bit-mask implementation or
compiler workaround remains in these sources.

## Module replacement, publication and successor SDK

Full four-validator replacement passes for a distinct candidate module parameterized with
a different immutable reward sink. Old administration and all-candidate-key possession
use separate digests. Seven tests reject spend-only approval, candidate self-approval,
old-module possession evidence, incomplete candidate keys, ordinary-intent signatures used
as possession, a candidate operation proof, and a mismatched candidate intent. The small
profile consumes 6,385,730 memory units and 3,569,150,567 CPU steps across all four scripts.
Maximum configuration/evidence profiles remain pending.

Removing duplicate checks from the transfer-only dispatch helper (the shared authorization
path still requires the same proof and empty possession list) reduced the module template
to **15,789 bytes**. All local checks pass. DevKit then accepted actual publication:
**16,268 transaction bytes**, leaving 116 bytes below the 16,384-byte limit for this
single-input/single-witness publication profile. Extra inputs, witnesses or metadata can
consume that margin; this result is not a guarantee for arbitrary publication builders.

`phase2TransferIntegrationTest --tests '*createFundAndSpendWithAllRequiredValidators'`
passed with fresh scripts and separate evidence. Creation, funding, an ordinary transfer,
recipient-substitution rejection and replay rejection are recorded in
[initial-transfer.json](evidence/initial-transfer.json), with corresponding
[template hashes](evidence/initial-transfer-templates.json). The transfer transaction is
`35e23711fc78a853664bcefa5fe9652ed7c668e76e024ef7c8e367f15c08a226`;
its actual paid fee is **1,189,809 lovelace**. Phase 2 script growth and full configuration
validation increase this above the archived 1,024,019-lovelace optimized Phase 1 profile.
No administration or recovery transaction has yet been claimed as ledger-qualified.

`AccountAdministration.successor` prepares canonical datums from the independent recovery
model and the effective ledger time bounds. It preserves all immutable fields and rejects
lifecycle/domain/target violations before returning a datum; it does not authenticate the
ledger source or authorize/submission-build an operation. Its outputs match all positive
compiled lifecycle and module-replacement cases. All 21 mutation mode/action combinations
also agree between the SDK and immutable state validator. The latter tests isolate state
mode enforcement, not the complete authorization chain.

The complete current `check` run passes **384 local tests**, one toolchain acceptance test,
Javadocs and protocol baseline verification. Full SDK attachment, clean-device restoration,
maximum budgets, additional adversarial/model sequences and actual delayed recovery remain
open in the acceptance ledger.

## SDK attachment and first actual mutations

`AccountAdministration.prepare` now verifies old-role or recovery-target signatures and
configuration possession before returning coordinated witnesses. `AccountMutation.attach`
authenticates the consumed inline state against the full NFT and independently supplied
script graph, binds its exact reference, checks complete reward-balance/receipt sets, and
attaches the same core redeemer under state Spending and checkpoint Rewarding. Candidate
module evidence remains separate. All preparation checks precede builder mutation; the
caller remains responsible for successor/receipt outputs, actual validity slots, plain
sponsor funding, reference scripts and key collateral, followed by full evaluation.

Fourteen adversarial SDK attachment cases reject without changing CCL intentions. Ten
additional tests compare SDK and compiled-module decisions for a new public key under an
old credential ID during configuration replacement and recovery. Missing possession,
old-key signatures and ordinary-intent signatures reject. ReplaceConfig rejects extraneous
retained-key evidence; completion permits additional valid target signatures under its
specified combined-evidence rule. Completion positives use the new spend key without the
lost old spend key.

The first live mutation exposed a transaction-builder staging issue: before adding fee
inputs, CCL presented a zero-ADA sponsor-change output to the evaluator. The immutable
state validator correctly rejected it. The fixture now explicitly supplies a plain sponsor
fee input before evaluation and re-establishes a separate disposable collateral UTxO after
creation consolidates the original funding. No validator invariant was relaxed.

`./gradlew phase2LifecycleIntegrationTest` now passes. It confirms six mutations and checks
the actual inline successor after each: freeze, unfreeze, configuration replacement,
recovery initiation, cancellation to Frozen, and independent unfreeze. Final state version
is 6, recovery sequence remains 1, and cancellation's cooldown is retained. Public
[evidence](evidence/short-lifecycle.json) records every transaction, fee, budget and successor.
All five script templates remain byte-identical to the initial-transfer template manifest.

| Mutation | Paid fee (lovelace) |
| --- | ---: |
| Freeze | 1,227,849 |
| Unfreeze | 1,229,018 |
| ReplaceConfig (same keys/configuration) | 1,353,367 |
| StartRecovery | 1,356,893 |
| CancelRecovery | 1,262,606 |
| Unfreeze after cancellation | 1,228,989 |

This short gate does not establish live module replacement, actual key rotation, delayed
completion, restoration after device loss, competing spend ordering or positive rewards
through recovery. Those remain explicit acceptance requirements.

### Ordered policy validation, budget profiles and locator restoration

The latest `check` passed 437 local tests plus toolchain acceptance, normative baseline
verification and Javadoc validation. The short lifecycle and four current-script transfer
profiles also passed on DevKit. Their public evidence and source/template hashes are in
[evidence/policy-qualified](evidence/policy-qualified/templates.json); the earlier
`budget-qualified` snapshot is preserved as an intermediate result.

Maximum mutation fixtures now exercise all six policies at eight members/threshold eight,
16 registry keys, full input/output/reference counts and positive withdrawal receipts at
late output indices. Ordered policy/registry and possession-proof merges avoid repeated
scans while rejecting unknown, duplicate and descending identifiers. The module-replacement
profile consumes 14,205,823 memory units and 9,142,621,496 CPU steps before the per-redeemer
5% allowance; both totals fit the configured limits with that allowance. These constructed
contexts do not establish exhaustive worst-case or full ledger acceptance for that profile.

Paid fees for the current transfer scripts are 1,122,225 lovelace for the ordinary profile,
1,332,742 for a whole deposit containing 140 native assets, 1,596,017 for the eight-input
partial native transfer, and 2,059,199 for eight inputs/eight recipients. These include the
complete transaction and reference-script costs and are profile-dependent.

The public locator format and provider-based authenticated restoration are implemented.
The short DevKit gate discards cached state/scripts and its primary key reference, connects
through a new backend client, retrieves scripts by their authenticated hashes, funds a new
collateral sponsor and unfreezes with the surviving independent defensive key. This proves
that restoration path on the same local ledger, not a second provider's trustworthiness,
secure memory erasure or delayed recovery completion.

The explicit `phase2DelayedRecoveryIntegrationTest` creates an all-new target configuration,
starts recovery, restores through the locator/new sponsor and waits against ledger timestamps
for the immutable 86,400,000ms minimum. It must not run with ordinary short integration tasks.
Its public pending evidence is under `build/phase2/recovery`; signing keys remain only in the
running process. A pending manifest is not a resumable signing backup. Positive rewards and
a subsequent recovered-key transfer remain separate acceptance requirements.

### Recovery boundaries and live authority replacement

Eleven additional compiled immutable-state/SDK boundary cases pass: immediately before,
exactly at and after the recovery deadline; wrong sequence and target; version, sequence
and delay overflow; cooldown boundary; and cancellation commitment substitution. These
bring the local suite to 448 passing tests. They isolate immutable enforcement and do not
claim module authorization or elapsed-time ledger evidence for fabricated contexts.

The real-delay worker confirmed initiation at transaction
`a580c1e852f9649cc7c71cc26b35fdfb8e756266c8d331d1a10083700ce6b9c1` and restored
its pending state through the locator and a fresh sponsor. Earliest completion is ledger
time `1788877344000`, or 2026-09-08 14:22:24 UTC (22:22:24 Singapore). Completion
is still pending. The running worker tests zero withdrawals; it does not satisfy the
separate positive-reward and post-recovery-transfer acceptance requirements.

Separate short DevKit cases successfully replaced all registry keys under old admin
signatures and all-new possession proofs, and replaced the module with a distinct,
separately parameterized candidate. The latter executed all four mandatory validators,
then froze and unfroze through the candidate. Neither changes the immutable custody
identity. Subsequent ordinary-transfer regression is included in both short test cases.

The final short run passed all three scenarios, including confirmed ordinary transfers after
both replacements (`db948240e17bbb806dc173bdc76f36197011cdc09c031e94b4d91ec495d9ea7a`
and `94875b89e296d47631fd51c98be542194988464722d6d031f5784ef111e8dfe7`).
Public transaction evidence and test-source hashes are in
[evidence/authority-replacement](evidence/authority-replacement/qualification.json).
`check` passed alongside that run. Competing freeze/spend orderings, positive-reward
mutations, actual delayed completion and the final comprehensive review remain open.
