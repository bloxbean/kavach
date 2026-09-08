# Transfer fee optimization

Status: development fee-optimization qualification complete, before Phase 2 (2026-09-07).
Historical Phase 1 acceptance remains recorded against its archived artifacts. The
optimized sources passed fresh qualification; this does not approve production use.

## Measurement

`AccountDevkitTest` now records the final transaction size, declared execution budgets,
each reference script's hash/charged bytes, the three fee components and any residual
between the calculated and paid fee. `FeeEvidence` uses exact decimal arithmetic and
the Conway reference tiers. It reports the already padded execution budgets, not an
additional margin. The actual confirmed transaction remains the measurement authority.

| Ordinary ADA transfer | Baseline | Complete-map counting candidate |
| --- | ---: | ---: |
| Paid fee (lovelace) | 1,208,773 | 1,192,034 |
| Size/fixed fee | 226,353 | 226,353 |
| Execution fee | 372,288 | 363,217 |
| Reference fee | 605,688 | 598,020 |
| Paid minus calculated | 4,444 | 4,444 |
| Charged reference bytes | 37,916 | 37,490 |

The residual is retained explicitly; it must not be mislabeled reference-script cost.
These are independent disposable-account runs. Script-hash ordering can affect execution
slightly, so future comparisons must not interpret every small difference as an optimization.
The SDK's 5% execution allowance is unchanged.

## Experiments

1. The pinned compiler's `pv11-costed` profile with its node-11.0.1 PV11 cost profile
   produced the same five template sizes as `pv11-safe`. The isolated build is an
   experiment; the project compiler configuration remains unchanged.
2. Counting every policy/name entry directly avoids constructing flattened triples when
   only a count is needed. It preserves count semantics and retains all separate key,
   quantity, state, signature and conservation checks. All 322 local tests and the
   toolchain/Javadoc gates pass. The ordinary DevKit transfer and its recipient/replay
   negatives pass, saving 16,739 lovelace in the recorded run. This is a small improvement;
   other profiles and real positive rewards still require qualification for these hashes.

Next work targets larger reductions in compiled bytes and repeated execution. Moving
authorization responsibilities or splitting genesis code requires an explicit security
argument, ADR changes where material, and tests of the complete validator composition.
No check will be dropped merely because its isolated benchmark is expensive.

## Further source candidates

- Wrapping standard-library asset lookups added script bytes and was discarded.
- Sharing list-size operations removed repeated generated counting loops. The shared
  helper now uses the pinned PV11 list-to-array/length builtins; it never decodes list
  elements. All existing list bounds remain in place. The confirmed ordinary transfer
  fee was 1,136,104 lovelace (`shared-array-ada-devkit.json`).
- The pinned API does not expose direct integer-comparison builtins. Shared arbitrary-
  precision comparison helpers avoid duplicating the generated three-way comparisons;
  no operand is narrowed to a Java primitive. The confirmed fee was 1,072,220 lovelace
  (`shared-predicate-ada-devkit.json`), about 11.3% below the new baseline.
- The current candidate checks exact constructor tags/arity instead of reconstructing
  selected records solely to compare their outer shape. This is valid only where all
  field types and constraints remain checked. Cross-validator equality bindings and the
  ADA-only asset comparison remain full comparisons. State mode remains exactly Normal;
  nested state identity/core/module fields are validated independently. Configuration
  keys, policy members, thresholds and signature bytes retain their complete checks.
  Added negative tests cover trailing fields and incorrect scalar types despite valid
  possession evidence. This candidate subsequently passed full DevKit qualification, including actual rewards.

Current template sizes: asset 10,569 bytes, checkpoint 8,004 bytes, authorization module
12,399 bytes, creation policy 6,007 bytes, sealed state 32 bytes. The protocol encoding,
validator partition and pinned compiler settings are unchanged. Current hashes differ
from the original Phase 1 qualified hashes and have fresh development qualification.

The full short suite passes for the shape candidate: 330 local tests, the toolchain
and Javadoc gates, and 12 DevKit tests including all four account profiles. The ordinary
transfer paid 1,027,123 lovelace (about 15% less than the fresh baseline). Its reference
fee is 489,120 lovelace for 31,440 charged bytes, execution 307,030, base 226,529 and
explicit residual 4,444. Full public results use the `shape-candidate-` prefix.

`feeOptimizationRewardIntegrationTest` completed fresh actual-refund qualification
for these hashes. Its evidence directory is `build/fee-optimization`; the completed
Phase 1 pending manifests are preserved. Its process held disposable signing keys; its public manifest is not resumable. Do not reset DevKit to accelerate it.

An isolated structural prototype moved accounting back into the checkpoint, reducing
the asset template to 1,064 bytes but producing a 17,030-byte checkpoint template before
parameters or transaction overhead. It still cannot be published within the 16,384-byte
transaction limit. This was a compilation measurement only, not a validated alternative;
the production-design proposal and working validator partition remain unchanged.

## Exact sponsor-witness counting

For these fixtures, CCL infers one required key witness from the sponsor's fee/collateral
inputs. `withSigner` adds another count used for dummy fee witnesses, yielding the observed
4,444-lovelace residual (101 bytes × 44 lovelace). Building unsigned with the inferred key
count and then signing with that same sponsor removes the duplicate estimate. All four
profiles passed again, with zero paid-minus-calculated residual. This is specific to the
known single-key witness setup; callers must still budget additional non-input witnesses.
The SDK example now documents that boundary.

| Profile | Final short-run fee (lovelace) |
| --- | ---: |
| Ordinary ADA | 1,024,019 |
| Eight-input native | 1,498,779 |
| Whole input with 140 tokens | 1,235,422 |
| Eight inputs / eight recipients | 1,964,658 |

Results are in `exact-witness-*.json`. The ordinary fee comprises 226,529 lovelace for
transaction bytes, 308,370 for declared execution and 489,120 for referenced scripts.
The positive-reward worker started before this builder-only change; it uses the same
validator hashes and the previous conservative witness count. Its fee is
reported with that distinction below. No pending reward test was restarted for a 4,444-lovelace
builder adjustment.

## Review and reproducibility

A fresh isolated compile reproduces all nineteen templates byte-for-byte. Extended
malformed intent tests cover missing/trailing fields, wrong tags and wrong Data kinds.
Test-only attacker hashing bypasses SDK shape guards to produce valid signatures over
malformed bytes; the compiled validators reject them. All 330 local tests pass.
The maximum combined eight-recipient/positive-reward fixture now uses 12,890,039 memory
and 7,537,878,512 CPU; with the unchanged 5% allowance: 13,534,547 and 7,914,772,444.
The actual positive-reward gate and evidence review also passed.

The confirmed reward transfer `f7221c76cdaede74de49de2acd2544bfac57070d9115a6cdedfdb3277133ae0a`
paid **1,119,799 lovelace**: base 237,441, execution 388,794, references 489,120
and the earlier conservative witness residual 4,444. Its two actual 1,000-ADA
withdrawals produced distinct 1,002-ADA outputs at the immutable sink, and both reward
balances cleared. Recipient substitution, receipt underpayment and replay were rejected.
The test passed after 1h 19m; full public evidence and independent DevKit output/withdrawal
queries are archived under `evidence/positive-reward-*.json`. This completes the final
gate without resetting DevKit, altering parameters or weakening reward accounting.

The requirement-by-requirement safety review and remaining economics are recorded in
[review.md](review.md).
