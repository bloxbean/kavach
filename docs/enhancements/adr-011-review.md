# ADR-011 enhancement review and qualification

Branch: `enhancement/scalability-security-wallet-review`.
This records the ADR-011 implementation stage. [ADR-012 qualification](adr-012-review.md)
supersedes new-reference custody with a separate publisher vault; figures and unchanged-source
statements below describe the earlier stage and are retained as historical evidence.
Status: development enhancements implemented; targeted final ledger regressions passed, 2026-09-19. Not an independent audit
or production approval. [Decision](../../adr/adr-011-economics-extensibility-and-wallet-safety.md),
[deployment specification](../../protocol/browser/deployment-economics.md).

## Findings and disposition

| Finding | Evidence | Disposition |
| --- | --- | --- |
| Fixed oversized, permanently locked reference capital | Dashboard published 5/6 × 80 ADA to NFT-less state script | New exact-minimum publisher-owned hosting; old outputs cannot be refunded |
| Transfer fee dominated partly by script bytes | Historical Phase 2: 1.122225 ADA, 0.573108 reference fee | Separate capital reduction from transfer-fee improvement; measure module specialization experiment |
| UI accepts more inputs than SDK/core | Dashboard 16 vs AccountTransfer/SpendLib 8 | Early eight-input limit and batch guidance; SDK boundary regression |
| Pre-genesis setup can be interrupted | In-memory setup plans; separate confirmed publications | Upfront funding preflight and restart disclosure; durable pre-genesis resume remains open |
| Arbitrary plugin compatibility overclaimed | Restricted script inputs/withdrawals/references; budget uses different core | Explicit capability/address matrix, no unchecked execution hook |
| State and registration capital not all refundable | Nondecreasing state ADA, no closure/burn/deregistration | Separate locked reserves from publisher capital in review UI |
| Reference owner can remove availability | New key-controlled hosting | Durable exact artifacts, historical/new discovery, exclusion from selectors and explicit repair |
| Maximum lifecycle and positive-reward UX incomplete | Existing Phase 2/browser/policy ledgers | Preserve open gates; do not infer completion from short tests |

Three independent agents reviewed both implementation and the draft ADR from contract,
economics and wallet perspectives. The initial draft was revised before implementation:
byte-authenticated durable artifacts, complete selection isolation, final-output minimum
checks, datum-growth/replacement-sponsor gates, publisher-key ownership, complete setup
costs and eight-input preflight were added. These are internal multi-reviewer checks, not
an external audit. No confirmed unauthorized-spend exploit was found in this bounded pass.

## Verification record

Baseline `./gradlew check` passed, including compiled UPLC suites, positive toolchain
acceptance, pinned binary verification, V1 baseline drift checks and Javadocs.

Added state-authentication adversarial cases cover wrong NFT name, doubled quantity,
duplicate matching references and reference-script-bearing state. A SDK regression proves
eight inputs prepare successfully and nine reject. The two focused suites pass 55 tests.
An initial unqualified filtered `test` invocation also reached the backend subproject,
which reported no matching tests; root-qualified `:test` passed.

Frontend economics rendering and prior regressions initially passed 17 tests; the final
suite has 19 passing tests, including state-growth funding and optional genesis references.
The pinned `dashboardWebBuild` passes.

DevKit's host-published 8080/10000 endpoints initially accepted TCP but returned no HTTP
response. Read-only container queries confirmed a running chain at protocol 12.0. A temporary
loopback-only proxy forwards requests through Docker exec without changing ledger state,
parameters, containers or active workers. The initial live attempt failed at faucet HTTP
connectivity, before contract validation. This is a failed attempt, not ledger qualification.

## Remaining release gates

- Real-delay browser recovery and positive-reward UI lifecycle qualification.
- Maximum old/new authority unions, maximum supported transaction shapes and independent audit.
- Portable artifact distribution/import and durable pre-genesis setup continuation across hosts.
- Independent frontend CBOR decoding and actual named wallet/hardware/physical-device testing.
- Concurrent ordinary/budget workload measurements and separately reviewed general policy ABI.
- Dashboard coin selection/batched consolidation beyond eight deposited inputs; current ordinary
  admission is capped at eight and larger sets need the qualified SDK batch workflow.
- Qualified closure/migration if state reserve reclaimability or new immutable semantics are wanted.
- Permissionless unsupported-deposit UX and broader application integration; no universal rescue claim.

No historical Phase 0/1 evidence, pending reward CBOR, delayed-recovery manifest or untracked
`contracts-aiken/` work is part of this change. Do not reset DevKit or shorten recovery for testing.

## Contract optimization experiment

[Structured metrics](evidence/auth-dispatch-experiment.json),
[candidate patch](evidence/auth-dispatch-candidate.patch),
[reproduction fixture](evidence/AuthDispatchExperimentTest.java), and
[scratch check log](evidence/auth-dispatch-check.log) retain the experiment.
The first specialization grew scripts; removing the now-unreachable transfer branches from
mutation-only helpers reduced browser bytes by 110 and mixed-policy bytes by 118. Every
configuration check remained. Maximum-registry browser combined CPU decreased
2,565,793,068→2,558,097,197 and memory 5,200,673→5,174,002 in the measured context.

No default contract source was changed. The small candidate savings did not justify breaking
historical unfinished mixed setup, whose final module is precommitted and may not yet have
been published. New retained candidate records help future upgrades but do not reconstruct
old unpublished bytes. Adoption requires archived old artifact support and live qualification.
Three compiled dispatch cases added to the main suite pass against the unchanged baseline,
including extra possession and invalid defensive configuration with otherwise valid evidence.

## Combined local checks

`./gradlew check dashboardWebBuild` passed: 561 root tests, eight backend tests and one
positive toolchain test, no failures/skips; Javadocs, pinned JuLC binaries and V1 schema
manifest passed. A final `:dashboard-app:backend:check dashboardWebBuild` also passed after
the live-test-discovered funding corrections. Frontend tests total 19 passes. Browser visual reinspection
could not complete: native Chrome/Safari surfaces returned `cgWindowNotFound`; component
render tests/build are not real-wallet UI qualification.

## Confirmed reference hosting and recovery of availability

The [fresh ordinary baseline](evidence/ordinary-transfer-baseline.json) confirmed with
1,120,859 lovelace: 226,353 base/bytes + 321,398 execution + 573,108 reference charges,
zero decomposition residual. This is scheme 0, not a before/after browser comparison.

The [new COSE hosting/repair flow](evidence/reference-repair.json) passed on the external
DevKit without resetting it: five publications, registration, minimum-funded genesis,
publisher spending one reference, a new backend instance, independent sponsor publication,
and same-address transfer. The final-code transfer paid **1.152553 ADA** (an earlier successful run paid 1.151363 ADA). Script/ABI sources remain
unchanged; [template identity evidence](evidence/templates.json) records the current artifacts.

| Five-reference COSE setup component | Previous dashboard | Confirmed new setup |
| --- | ---: | ---: |
| Reference output capital | 400 ADA, permanently locked | 223.895880 ADA, publisher-key controlled |
| Account-state reserve | 12 ADA, locked | 2.762710 ADA, locked |
| Registration reserve | 4 ADA | 4 ADA |
| Estimated setup network allowance | No full quote | 14 ADA, estimate rather than actual total |
| Selected collateral retained | No separate whole-setup quote | 20 ADA in this fixture |

The fixture's total estimate was 264.658590 ADA excluding the reserved identity seed.
This is not the six-reference mixed profile or a network-independent price. The publisher's
capital is not recovered by Kavach credential recovery. An independent provider can restore
availability but cannot recover another publisher's lost wallet keys/capital.

Live tests exposed and fixed zero-based funding pagination and registration deposit selection.
Deliberately consuming a reference also exposed a pinned builder pricing limitation: a generic
key transaction offered 171,353 lovelace while the node required 327,605 because the consumed
output carried a reference script. The reclaim fixture uses an explicitly disclosed 1 ADA
fee to test custody, not minimum-fee pricing. The dashboard does not offer automatic reclaim;
UI/docs require a reference-aware wallet/tool and applicable network/reference fees.

The initial failed runs are preserved under `build/enhancements/adr-011` and are not counted
as successful ledger evidence. No long-running recovery or reward worker was restarted.

The review iteration also added visible exact state-growth funding before transaction
signing and a final successor-reserve equality check. Genesis-only NFT references are
classified optional after creation, avoiding unnecessary repair prompts. Public candidate
and setup hints are written atomically; the bounded discovery limit is disclosed accurately.

Final funding-path review found and corrected the special recipient-equals-sponsor input
selector, which also needed reference-output exclusion. The final-body guard rejected that
selection before signing. Reservations remain per setup plan; concurrent operations using
the same sponsor can invalidate another setup. The UI and guide recommend sequential setup
per fee wallet. Cross-plan reservation coordination remains an operational enhancement.

## Final live qualification

Eight targeted dashboard cases passed on the final code with zero failures, errors or skips:
three mixed-signing profiles (COSE count 0, 1 and 3), insufficient-funding rejection before
publication, publisher removal/restart/independent reference repair, periodic-budget lifecycle,
and both browser-mode account lifecycles. The latter exercise transfers, consolidation,
freeze/unfreeze, key rotation, cross-mode module replacement and recovery start/cancellation;
they do **not** complete the real recovery delay. The separate ordinary transfer benchmark
also passed. These use synthetic wallet signatures with actual ledger validation.

[Final validation summary](evidence/final-validation.json),
[mixed results](evidence/mixed-final-test-results.xml) and
[remaining dashboard results](evidence/dashboard-final-test-results.xml) preserve the case
counts and public transaction identifiers. Final funding isolation was exercised by payments
back to the sponsor; state-reserve disclosure was checked against actual successor outputs
during budget and browser lifecycle changes. Failed intermediate selection/provider/pricing
attempts are retained locally and are not counted as successful qualification.

The final implementation changes deployment tooling, UI and qualification tests; current
contract/SDK implementation sources, V1 schema and signing encodings remain unchanged. The
recurring fee reduction milestone and release gates above remain open.
