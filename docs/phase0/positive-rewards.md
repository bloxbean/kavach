# Real positive-reward ledger qualification — 2026-09-07

Both Phase 0 reward-credit experiments passed on the existing Yaci DevKit, without resetting the cluster or changing protocol parameters. Information-proposal deposits expired and credited non-delegating script reward accounts. These were actual ledger balances, not fabricated script contexts. All funds and authorities were disposable test fixtures.

| Case | Actual credit | Node result |
| --- | ---: | --- |
| Single checkpoint | 1,000,000,000 lovelace | Zero and partial withdrawals reject; full withdrawal confirms; balance returns to zero |
| Paired core and module | 1,000,000,000 lovelace each | Shared receipt index rejects; distinct receipts to the same immutable sink confirm; both balances return to zero |

Single full withdrawal: `d588aa968fbf0b2a276b79c1e50c3aa1dd3d1c5d7ee96673e5ebea3b1bf1a68d`. Paired full withdrawal: `1e36f0cefa7be79e9abe18f944856ec66de4a9b28f3cb937fae221f30bc3a6ef` (10,868 transaction bytes). Registration/proposal hashes, deployed script data, allocations and node failure responses are retained in the [single evidence](evidence/positive-reward-2026-09-07.json) and [paired evidence](evidence/paired-reward-2026-09-07.json).

The single run took 1h 17m 43s. The paired experiment resumed from its existing proposal and prebuilt signed transactions after correcting an inherited harness timeout; the resumed run took 1h 1m 17s. No additional paired proposal was submitted for the resume. The original failed single attempt remains recorded separately; it proved a credit but failed construction before its full-withdrawal branch and is not counted as a passing test.

The probes establish positive-balance checkpoint authorization and receipt composition. Their prebuilt no-expiry transactions are experimental fixtures. Production intents retain their bounded finite validity interval and must be freshly assembled/authorized around actual balances. Complete wallet spending and recovery with positive reward balances remain required in Phases 1 and 2; neither probe is a production account validator or working recovery flow.

The [completion verifier](../../scripts/record_phase0_completion.py) requires passing reports from both long tests, all fast tests and an independent clean build, checks the source/template manifests and schema evidence, and writes the [completion record](evidence/completion-2026-09-07.json). It previously refused closure while the long-test reports were incomplete.
