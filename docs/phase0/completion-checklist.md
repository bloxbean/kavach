# Phase 0 acceptance ledger

Status: **complete for ADR-001 Phase 0 — specification and feasibility**, verified on 2026-09-07. All applicable Phase 0 gates have evidence. This is not a usable account implementation or production approval. Phase 1 transfer implementation and Phase 2 administration/recovery enforcement remain separate obligations. See the [qualification report](qualification.md), [real reward evidence](positive-rewards.md) and [machine-checked completion record](evidence/completion-2026-09-07.json).

Final verification: **217 local tests/diagnostics, 1 positive compiler acceptance test and 10 live DevKit tests**, with zero failures or skips; 21 independent CDDL fixtures plus structural rejection; 14 compiled probe templates reproduced by an independent clean build. One local diagnostic records known upstream client-bridge defects rather than asserting bridge conformance.

| Gate | Status | Evidence / remaining work |
| --- | --- | --- |
| Pinned build, compiler output and signature scheme | Passed for experiments | Source-fixed JuLC, JAR manifest, raw signature vectors, positive compiler gate and live wrong-signature/identity-key rejection |
| Acyclic initialization and unique state identity | Passed for experiments | Creator-bound one-shot NFT live tests, compiled deployment vector, full-state encoding and genesis possession vectors; production creation implementation Phase 1 |
| Explicit registration and purpose dispatch | Passed | Legacy/explicit registration, missing witness/deregistration/duplicate rejection, controlled legacy re-registration, same-transaction ordering rejection |
| Positive reward balances and full withdrawal | Passed | Actual 1,000 ADA information-proposal refund; zero/partial withdrawals rejected and full withdrawal confirmed. Paired shared-sink experiment also passed. |
| Multi-receipt disposition and module binding | Passed | Paired account/action/purpose binding; two real positive balances drained to distinct shared-sink outputs; node rejects receipt reuse; compiled top-up and double-counting tests |
| Reference-script availability/fallback | Passed | Two locked reference copies and independent full witness confirm with identical script hash |
| Normative wire schema and canonical vectors | Reviewed baseline | Nine actions, Normal/Pending state, separate proof domains, deterministic envelope/state rendering, CDDL 0.12.14 structural validation and drift manifest |
| Numeric limits and boundary behavior | Passed at component scope | Joint byte/count/quantity/time/proof caps; malformed/overflow rejection; whole-UTxO route for deposits exceeding partial-transfer limits |
| Recovery specification/model | Passed at specification scope | Independent defensive roles, cooldown, target retention/possession, checked arithmetic and 10,000 model attempts; actual validator enforcement Phase 2 |
| Feasibility budgets and sizes | Measured | Compiled stress cases, 128 asset distributions, six transaction serialization models and minimum ADA against PV11 limits; integrated production budgets remain Phase 1/2 qualification |
| CIP-113 decision | Outcome B selected | Ordinary V1 deferral, no hash-preservation promise and potential migration explicitly recorded in both ADRs |
| Review/reproducibility | Passed for current baseline | Independent clean build reproduces 14 probe templates; manifest rejection checked; bridge ordering defects reproduced and experimental workarounds documented |

No production core hashes are frozen. A green diagnostic that records an upstream defect is not a claim that the defect is fixed. Final production creation, module ABI enforcement, asset/state transitions, recovery UX, integrated budgets and independent audit are later-phase gates.
