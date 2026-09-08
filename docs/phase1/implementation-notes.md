# Phase 1 implementation notes

Status: Phase 1 development qualification complete; not production approval. See ADR-003 for the immutable accounting-anchor partition.

## Compiler boundaries

- Shared record containers must be `@OnchainLibrary` and explicitly imported as a container, in addition to nested type imports, for this pinned processor to discover them.
- Give the genesis state extracted from an inline-datum switch an explicit `AccountState` declaration. The inferred `var` form caused an unbound-variable compiler failure.
- Minting-purpose policy IDs are already unwrapped by this pinned compiler: `Builtins.toByteString(policyId)` is the supported conversion. Calling `.hash()` on that projected value compiled but failed UPLC with a double `UnBData`.
- Never reassign an accumulator to its own transformed value outside its loop. Use a new local for the reversed recipient-index list.
- All tests execute emitted artifacts. No source or artifact rewriting is used to pass acceptance; these are ordinary source-level implementation choices.

## Reference scripts and fees

CCL 0.8.0-pre5's `removeDuplicateScriptWitnesses(true)` runs after balancing. Attaching the same full scripts and supplying their references therefore calculated the byte fee with witnesses that were subsequently removed. The first node-confirmed transfer paid 3,078,738 lovelace; creation paid 1,897,423.

Using the supported `preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses())` hook removes reference-backed witnesses before fee calculation. A second run of the same contract code confirmed creation at 901,219 lovelace and the simple ADA transfer at 1,352,643 lovelace. These are disposable DevKit observations, not a fixed production quote. The builder must provide the correct references and independently validate the final transaction; merely listing a script for fee calculation does not establish its availability or authorization.

Initial successful transaction IDs and protocol parameters are retained in `evidence/first-transfer-2026-09-07.json`. Subsequent native-asset, boundary, malformed-data, live rejection and positive-reward qualification passed; see the acceptance ledger for final evidence. Reference publication outputs in this test deliberately hold 80 ADA each; that retained deposit is not the network transaction fee or a measured minimum.

## Native accounting and current qualification

The initial Data-based full transaction stress fixture required 27.54 million memory units,
above DevKit's 16.5 million limit. Linear matching of sorted proofs and inputs, removal of
redundant checks whose invariants are established by immutable genesis custody, and the
candidate PV11 native-Value accounting path reduced execution cost. The final combined
fixture additionally exercises eight base-address recipients, two positive reward receipts,
eight account inputs, eight sponsor inputs, four references, sixteen outputs/signatories,
twelve native entries, sixteen registry keys, all six roles with eight members, and eight
spend signatures. It requires 15,267,552 memory units and 8,377,552,336 CPU steps.
The SDK's 5% allowance totals 16,030,934 memory and 8,796,429,958 CPU, within the
current node limits. This synthetic composition is not an exhaustive worst-case proof.

Recipient membership uses a two-byte mask derived solely from signed indices 0..15;
ordering, uniqueness and actual output checks still apply. The ADA-only shortcut verifies
both complete singleton maps. Aggregate quantity checks use already validated input keys,
then bound the complete union and require positive ADA. No native asset is omitted.
Cursor-based recipient alternatives failed valid compiled cases and were discarded;
a raw-map iteration alternative passed but increased cost. Accepted scripts are ordinary,
unmodified compiler output.

Full registry uniqueness and defensive-role checks occur at genesis; ordinary spends
authenticate immutable state before narrower spend-policy checks. Every future state or
module installation path must re-establish these invariants. The native implementation
retains complete asset-map equality, individual and aggregate quantity bounds and
positive-receipt disjointness. The actual positive-reward ledger gate passed with the final scripts.

An exact backend execution estimate was insufficient for node acceptance on an earlier
transfer. An intermediate 10% margin passed short profiles but exceeded the budget of a
combined stress fixture. The SDK now defaults to an explicit 5% per-redeemer allowance,
rounds upward, checks complete estimates and rejects padded totals above current limits.
All four final short DevKit profiles passed with that allowance. It is a builder precaution,
not a protocol change or universal estimator-error bound. Historical evidence files retain
earlier scripts/fees; final short-run evidence and manifests use the `-current-2026-09-07`
suffix. Fees exclude reference publication, registration deposits and retained output value.

## Java documentation

Validator entrypoints document immutable parameter order, authorization boundaries,
rejection behavior and phase limitations. Wire records document field meanings and units;
SDK and protocol helpers distinguish encoding/shape validation from ledger authentication
and key possession. Package documentation separates probes, contracts, authorization,
SDK helpers and executable specifications. `./gradlew check` includes Javadoc syntax and
link validation; missing-comment lint for generated members is excluded, so documentation
coverage still requires review. The documentation pass preserved all fourteen Phase 0
compiled template hashes. Full Phase 1 development acceptance is complete.
