# ADR-012 publisher reference vault qualification

Branch: `enhancement/scalability-security-wallet-review`.
Status: scoped development implementation and targeted qualification complete, 2026-09-19.
No production approval; remaining boundaries below still apply.

[Decision and implementation plan](../../adr/adr-012-publisher-protected-reference-vault.md),
[vault wire specification](../../protocol/reference-vault/specification.md),
[deployment behavior](../../protocol/browser/deployment-economics.md).

## Review scope

Three internal reviewers cover validator authorization/encoding, backend custody/economics,
and wallet review/disclosure. The vault separates reference capital from ordinary wallet coin
selection while preserving the publisher's intentional right to reclaim it. It neither grants
account authority nor guarantees continuous reference availability. Publisher-key loss and
malicious-signature risks remain; this is not an independent external audit.

The immutable account contracts, sealed V1 schema, compiler pins and existing deployment
addresses must remain unchanged. The new validator has a separately versioned schema and
artifact. Legacy locked/key-address copies remain supported without automatic migration.

## Required qualification

| Gate | Status |
| --- | --- |
| Strict compiled signature/purpose/parameter/redeemer checks and execution budgets | Six methods pass, including malformed nested shapes and wrong/missing owner/purpose |
| SDK canonical encoding and golden vectors, deterministic address derivation | Parameters/redeemer vectors pass; publisher/full-account changes alter identity |
| Exact artifact/record integrity and backward-compatible discovery | Local record restart/tamper cases pass; live new vault discovery passes; old discovery paths retained |
| Setup minimum ADA and no incidental vault funding/collateral selection | Creation, mixed activation and both full short lifecycles pass with vault-hosted references |
| Explicit exact-UTxO reclaim, full return, correct final fees and stale checks | Live reclaim passes, full 47.091060 ADA return, zero fee-decomposition residual |
| Publisher witness rejection at ledger; successful reclaim/restart/independent repair | Final flow passes, including specific node evaluation rejection and pre-genesis preparation |
| Ordinary same-address transfer without publisher account authority | First complete flow passes after independent publisher repair |
| Mixed-signing setup regression | Three profiles pass, including restart, final activation and transfers |
| UI acknowledgement, custody/owner/outpoint/amount/fee display, rendering tests/build | 24 frontend tests and pinned build pass; mocked desktop/mobile inspected |
| Full check, schema baseline and unchanged prior artifact hashes | Initial full check passes: 568 root, nine backend and one toolchain test; final backend recheck and pinned web build also pass after live fixes |

## Pinned fee-library finding

[Bytecode review](evidence/adr-012/ccl-consumed-reference-fee-review.json) confirms the pinned
CCL only includes consumed-reference fees when a transaction has a script-data hash. The
previous ADR-011 plain-key reclaim fixture did not meet that condition. The new vault uses an
actual script spending redeemer with the custody validator supplied as a witness; it therefore
meets that code path without adding a reference input. Node-paid fee decomposition is still
required and must distinguish custody execution, base bytes and the consumed hosted script.
No fixed oversized reclaim fee is accepted as qualification of the new builder.

## Remaining boundaries

- Publishers may intentionally remove all active copies. Repair requires available exact
  artifacts and funding; account funds remain protected but operations may be unavailable.
- The UI lists one discovered copy per current account graph hash, preferring historical copies.
  It is not an inventory of duplicate, retired-module or orphaned pre-genesis publications.
  Such vault copies remain accessible through exact-outpoint API/SDK reclamation with retained
  locator/vault data. Preparation does not require live account state. Shared copies from a
  different account namespace are usable references but not reclaimable from this account view.
- Native assets on a selected reference are preserved and displayed by the implementation;
  live reclamation samples use ordinary ADA-only reference outputs. Mistaken token deposits
  have not received separate live qualification.
- Local public profile/artifact backups survive service restarts. Locator-only recovery cannot
  recreate lost script bytes. Portable UI backup/import and proactive alerts remain unfinished.
- Lost publisher keys are not recovered by Kavach account recovery. Future vault versions must
  retain old derivation/artifact support before changing the current template.
- The UI guides explicit approvals, but independent frontend transaction decoding and actual
  named-wallet/hardware capture remain open, as do independent audit and existing Phase 2 gates.
- DevKit, pending rewards and delayed-recovery workers must not be reset or altered.

## Local validation and visual scope

`./gradlew check dashboardWebBuild` passed with no failures/skips: 568 root tests, nine
backend tests and one toolchain acceptance test. The new locator identity accessor returns
defensive copies without contacting a ledger provider, allowing publisher custody handling
to remain independent of account-state restoration. It is not account authentication.

[Compiled vault evidence](../reference-vault/evidence/compiled-vault.json) records a 697-byte
applied fixture, 17,573,721 CPU and 61,511 memory. These are measured synthetic-context
costs, not the final ledger redeemer allowance. All 26 pre-existing contract artifact files
remain unchanged; [ten account-template CBOR hashes](evidence/adr-012/unchanged-account-templates.json)
were also independently compared with the ADR-011 baseline and match; only the aggregate manifest includes the new vault.

The [UI inspection scope](evidence/adr-012/ui-scope.md) records mocked API data with no wallet
or ledger action. Desktop and 390px mobile reclaim dialogs were inspected without horizontal
overflow. Component tests cover acknowledgement, exact output identity, wrong publisher,
legacy-copy exclusion, returned amounts and publisher-versus-account-authority wording.

## Initial live results and corrections

The first complete vault flow passed on the externally running DevKit: publication/genesis,
wrong publisher-witness rejection, explicit owner reclamation, stale outpoint rejection,
backend restart, missing-reference display data, independent-sponsor repair and same-address
account transfer. In this sample the five references require 223.292480 ADA. The smaller
enterprise vault address accounts for a 0.603400 ADA reduction versus the earlier five
base-address outputs; this is profile/parameter-dependent capital, not a transfer-fee saving.

The reclaimed asset-validator reference returned its full 47.091060 ADA. Its signed
1,266-byte transaction paid 376,944 lovelace: 211,085 base/bytes + 5,044 execution + 160,815
consumed-reference charge for 10,721 bytes. The calculated sum exactly matches the paid fee.
The post-repair transfer paid 1.151101 ADA. No fixed oversized fee override was used.

Two initial attempts failed locally before submitting a reclaim: required-signer comparison
used the wrong Java representation, then the exact-return assertion detected CCL deducting
fees from reclaimed capital. The first was corrected to byte comparison. The second uses
explicit separate sponsor funding and the existing output-preserving fee-balancing helper;
the full-value guard remains in place. Failed runs are retained under
`build/enhancements/adr-012/vault-live-{1,2}.log` and do not count as ledger successes.

## Final five-case batch

[Five-case results](evidence/adr-012/vault-final-five.xml) passed with zero failures/errors/
skips: mixed profiles with COSE counts 0, 1 and 3, unaffordable setup rejection, and the
reference-vault flow. The latter includes pre-genesis reclaim preparation using a locator
without live state, wrong owner/acknowledgement rejection, missing publisher witness rejection
by ledger submission and wrong required-signatory rejection by the node evaluator. It then
confirms owner reclamation, backend restart, independent repair and same-address transfer.

[Initial measured flow](evidence/adr-012/reference-vault-first-pass.json) and
[five-case measured flow](evidence/adr-012/reference-vault-five-pass.json) retain public fee
and transaction evidence. The final lifecycle/strengthened malformed-outpoint run also passed, as recorded below.

## Final qualification outcome

[Final three-case results](evidence/adr-012/vault-final-three.xml) passed with zero failures,
errors or skips: the strengthened vault flow and both browser-mode lifecycle workflows.
Combined with the five-case batch there are **seven distinct live workflows**, eight passing
executions because the focused vault case was repeated after adding stronger rejection
assertions. Lifecycle coverage includes consolidation, freeze/unfreeze, key rotation,
cross-mode replacement, recovery start/cancellation and subsequent full account-asset transfer;
it does not complete the real recovery delay or close the account-state reserve.

The final focused case explicitly rejects substituted script hashes/outpoints, stale reuse,
wrong owner and missing acknowledgement. The node evaluator reports an `EvaluationFailure`
for the wrong required signatory; ledger submission rejects a missing publisher witness.
Valid owner reclamation, independent republishing and subsequent transfer confirm.

[Final public economics](evidence/adr-012/reference-vault-final.json) records:

| Component | Final measured sample |
| --- | ---: |
| Five reference outputs in publisher vault | 223.292480 ADA |
| Permanently locked account state | 2.762710 ADA |
| Registration deposits | 4 ADA |
| Complete funding estimate, including 14 ADA fee allowance and 20 ADA collateral | 264.055190 ADA, seed excluded |
| Full selected reference capital returned | 47.091060 ADA |
| Reclamation network fee, funded separately | 0.376944 ADA |
| Subsequent account transfer network fee | 1.149648 ADA |

Reclamation's fee decomposition remains exact with zero residual. The script wrapper's
execution is paid on reclamation, not ordinary reference use. These are disposable DevKit
samples; the complete funding figure includes reserves/estimates and is not a spent-fee total.

[Final validation summary](evidence/adr-012/final-validation.json) records local counts and
final live cases. Full check, final backend check, pinned web build, 24 frontend tests and
local documentation-link/diff checks pass. Three internal reviews found no concrete theft or
authorization bypass in this scoped change; this is not a substitute for independent audit.
No external DevKit reset, parameter change, reward/recovery worker restart or unrelated
`contracts-aiken/` modification was performed.
