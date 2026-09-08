# Fee optimization review

Status: development qualification and implementation self-review complete, including
the actual positive-reward ledger test. This is not independent audit or production approval.

## Preservation of protocol rules

| Change | Security argument and evidence |
| --- | --- |
| Complete Value entry counts | Counts all policy/name entries, including ADA, instead of allocating flattened triples. Existing NFT identity, quantity, positive ADA, mint, conservation and entry limits remain. All original creation/value attacks still reject. |
| Shared list counts | Erases element types only to count the complete list using PV11 array builtins. No element is decoded or trusted by the counter. Existing element validation, ordering and bounds remain at their owning checks; boundary and combined-budget tests pass. |
| Shared integer predicates | Uses arbitrary-precision comparisons throughout, with the same strict/inclusive inequalities. No `longValue` conversion of quantities is introduced. Exact fee, maximum quantity, aggregate overflow, threshold and validity tests pass. |
| Exact constructor shape | Tag and arity checks reject wrong tags, missing fields and trailing fields. They replace reconstruction only where the original field checks remain. Core/NFT/module references retain hash/type validation; Normal state retains all scalar bounds, nested binding checks and exact mode. Configuration, key, threshold and signature records retain their element/type/role checks. |
| Cross-validator binding | Full equality comparisons between core/module intents, proofs and receipts remain. Signature digest domains, immutable state authentication, mandatory accounting anchor and full sink addresses are unchanged. No check is delegated to a different validator. |
| Single sponsor signing | The controlled fixtures require only the input-owner sponsor key. CCL infers it from resolved inputs; building unsigned and signing once avoids adding another dummy witness count. All four final transfer profiles confirm with zero fee-decomposition residual. This must not be generalized to additional non-input witnesses. |

The malformed-intent test signer deliberately bypasses SDK Data guards. Thus wrong Data
kinds and malformed constructor records have valid attacker-produced signatures and still
fail compiled validation. The SDK's own strict guards remain unchanged. Added genesis
tests likewise regenerate possession evidence for malformed configuration bytes, including
trailing fields and incorrect scalar types.

The V1 normative schema and conformance fixtures are unchanged. Phase 0's fourteen
templates are unchanged. All five candidate Phase 1 templates reproduce byte-for-byte
in a fresh isolated build. No compiler binary changes or post-compilation rewrites were
introduced. The 5% execution allowance is unchanged and still rejects over-limit totals.

## Acceptance evidence

- [x] Fresh baseline and staged confirmed-fee measurements are archived.
- [x] Exact final fee components reconcile with all four paid transfer fees.
- [x] 330 local tests, pinned toolchain gate and Javadoc validation pass.
- [x] Full short regression passes 12 DevKit tests; final sponsor-count variant separately passes all four account profiles.
- [x] Combined maximum-profile budgets, fresh-build reproducibility and unchanged baseline schema are verified.
- [x] Actual positive rewards execute through the same final validator hashes; final receipts, rejection evidence and fee are archived.

The completed reward worker used the final validator hashes but the earlier conservative
dummy-witness count. This affects only the small fee residual, not validator acceptance.
The completed Phase 1 pending files and evidence have been preserved separately.
The reward transfer paid 1,119,799 lovelace, including the documented 4,444-lovelace
conservative witness estimate. Actual ledger withdrawals of 1,000 ADA each produced
two distinct 1,002-ADA receipts at the immutable sink; both balances cleared. Recipient
substitution, receipt underpayment and replay were rejected. See the
[public result](evidence/positive-reward-devkit-evidence.json),
[outputs](evidence/positive-reward-utxos.json) and
[withdrawals](evidence/positive-reward-withdrawals.json).

## Economics and scope before Phase 2

The measured ordinary fee fell from 1,208,773 to 1,024,019 lovelace (about 15.3%).
Reference bytes fell from 37,916 to 31,440. Even with hypothetical zero execution cost,
the current ordinary transaction's byte and reference fees total 715,649 lovelace.
Consequently, this implementation does not yet approach conventional key-wallet fees;
the improvement is not a claim of acceptable consumer-wallet economics.

The changes also create implementation headroom: the module template is 12,399 bytes,
down from 15,808, and the largest measured combined profile fits its 5% allowance with
13,534,547 memory units rather than 16,030,934. These are measured scenarios, not an
exhaustive worst-case proof or a guarantee that all Phase 2 additions will fit.

The tested cost-aware compiler profile did not reduce these scripts. Shared asset-lookup
wrappers increased bytes and were discarded. A compile-only merged-checkpoint prototype
still required 17,030 template bytes before parameters and could not be published within
the transaction limit; it was not adopted. Splitting genesis into another checkpoint or
weakening independent module authentication was not introduced for an unmeasured saving.
Further substantial reductions require better generated code or a separately reviewed
architectural change. Phase 2 should retain these fee/budget benchmarks and undergo a
final optimization pass covering administration, recovery and transfer together.

Accounts still have sealed state and require external collateral. Recovery, administration,
CIP-113, staking, governance and production deployment remain outside this work.
