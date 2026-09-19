# ADR-013: Lower-cost deployment, bounded extensibility and wallet safety

Status: Accepted implementation direction after three independent reviews (2026-09-19).
Development qualification only; implementation and release gates remain evidence-based.
[ADR-014](adr-014-publisher-protected-reference-vault.md) supersedes key-address hosting
for new publications with publisher-protected script vaults; existing copies remain supported.

Related: [architecture](adr-001-kavach-programmable-smart-account-architecture.md),
[CIP-113 boundary](adr-002-kavach-cip113-interoperability.md),
[browser authentication](adr-005-browser-wallet-authentication-and-demo.md),
[periodic budgets](adr-008-optional-periodic-budget.md),
[creation](adr-009-per-key-account-creation.md),
[sponsorship](adr-010-intent-execution-and-fee-sponsorship.md).

## Problem and evidence

The reviewed dashboard publishes five 80 ADA references, or six/480 ADA for mixed
creation, and a 12 ADA state output. References are sent to the state script without
its NFT and are permanently unspendable. This source does not establish the reported
219 ADA recoverable cost. Existing deposits cannot be made refundable by an SDK update.

Historical Phase 2 ordinary transfer evidence in `build/phase2/transfers/devkit-evidence.json`
reports 1,122,225 lovelace: 226,529 base/bytes, 322,588 execution, 573,108 reference
charges for 36,106 script bytes. This is not a current mixed-policy quote. Reference
charges alone are a substantial floor; lower reference-output deposits do not lower
per-transfer script-reference charges. Account-specific asset scripts and random deployment
domains also prevent naive global reference reuse.

The current immutable core enforces exact account inputs, full native-asset conservation,
disjoint allocation, lifecycle, replay and rewarding-purpose authorization bindings. Four
reference inputs and restricted script inputs/withdrawals bound composition. A budget-capable
asset validator is a different address, and its consumed counter serializes spends.

## Decision

Preserve deployed core artifacts and the V1 wire baseline. Improve off-chain deployment,
funding disclosure and script availability first, and measure the resulting transactions.
Any subsequently optimized core is a new deployment with explicit migration; it cannot
silently change an existing account address. No audit or absence-of-loss guarantee follows
from this engineering review or passing tests.

### Reference custody and capital

For new dashboard publications, calculate minimum lovelace from the exact serialized
reference output and current ledger parameters, rather than a fixed 80 ADA. The output's
address, script bytes, datum and lovelace encoding must all participate in calculation.
Use a conservative stable result if changing the integer encoding changes the minimum.

Introduce publisher-owned key-address hosting as an explicit development alternative to
ADR-001 section 16.3's permanently locked references. Reference ownership grants no account
authority. It does allow a publisher/wallet coin selector to remove a reference, interrupting
availability. Present this distinction before publication and retain exact script bytes.
Preserve discovery of historical locked references. Discover new references using public
hosting records, authenticate their exact expected script hash, and recheck live availability
when constructing each transaction. An untrusted record is a discovery hint, never module
or account authorization. Funding/collateral selectors must exclude reference outputs, including automatic balancing
and collateral selection; resolve and assert final-body inputs before signing. Verify fetched
V3 script bytes against the expected hash, live reference presence and network. Select
duplicate valid copies deterministically; missing or corrupt hints fail safely.

When a reference is missing, offer separately approved publication of the same retained
script at the current parameters; do not replace a validator or require the old publisher's
key. Rebuilding signed transaction bodies requires new witnesses; changed intent fields
require new authority consent. Test owner removal followed by restart and an independent sponsor
republishing retained bytes and completing a same-address spend. Do not automatically reclaim reference outputs. Users may
reclaim publisher-controlled capital through their wallet only with the disclosed availability
consequence. Persistent hosting hints contain no secrets and must survive service restart. Retain a
durable public artifact bundle with exact applied script bytes, language/hash, deployment
and hosting hints; recompile-after-upgrade is not recovery of an old script. Repair must
work even if provider script history is unavailable. Hosting records never introduce
provider URLs or account authority. Publisher capital belongs to the fee-wallet key, not
Kavach administration/recovery; losing that key can lose this capital independently.
Redundant permanent/shared hosting remains an optional operator strategy, not a per-account
requirement or permission to charge for duplicate publication.

The NFT-bearing account-state reserve is separate and remains locked: V1 has no closure
or burn. Stake-registration deposits and optional budget-counter reserves must likewise not
be advertised as refundable. Account state may be funded at its current exact minimum with
externally funded increases for later datum growth; old state reserves cannot be reduced.
Check the final serialized genesis and successor outputs. Qualify minimum-funded
Normal→RecoveryPending→completion, configuration growth and replacement-sponsor top-ups
before describing minimum-funded recovery as proven.

### Wallet economics and funding safety

Expose setup reference capital, permanently locked state reserve, registration deposits,
network fees and collateral separately. Quotes bind the selected scripts/configuration,
network parameters and publisher; they are estimates until final evaluation. Include every
publication (including mixed activation), all registration deposits and transaction count.
Separate minimum required output capital from estimated fees and retained collateral. Show the actual
transaction fee before transaction signing. Never equate sponsor funding with account
approval or imply collateral is a successful transaction charge.

Preflight funding before the first publication. Reject obviously unaffordable setup while
retaining a fee/collateral reserve and the one-shot seed. Final balance/evaluation remains
authoritative because fees, UTxOs and parameters can change. Disclose partial-setup and
pre-genesis restart limits until public resumable setup manifests are qualified. Within a
live setup, retries reuse its seed/domain/commitments and confirmed steps. Distinguish
insufficient aggregate balance from insufficient separate seed/funding/collateral UTxOs;
reserved seed/collateral cannot be counted as freely spendable setup funds.

### Address-preserving extension contract

Compatible authentication/policy modules may be replaced only under old administration,
with exact candidate hash/ABI/configuration and target possession validation. A module may
compose bounded stateless authorization conditions internally using the existing opaque
configuration boundary. It cannot weaken immutable value, replay, mode or purpose rules.
A malicious or broken authorized module can still grant excess authority or block recovery;
configuration validation is not a proof that arbitrary module code is safe.

Document capabilities by deployment/profile: supported operations, signing schemes, policy
semantics, required state, purpose/reference counts, configuration/evidence limits, reward
handling, recovery support and qualification evidence. Unknown capabilities fail closed.
No UI registry supplies on-chain authority. Personal and enterprise threshold policies use
the same separated spend/admin/recovery/defensive authorities; role names alone are insufficient.

Arbitrary dApp conditions, new ledger cryptography, external policy withdrawals, independent
recovery modules, closure, CIP-113 and new mutable counters require separate ABI/core feasibility
and security gates. They are not universally installable at today's address. Before any future
core freeze, prototype a bounded policy-composition ABI with operation/domain/digest binding,
receipt disjointness and transaction-wide resource budgets. Do not ship a universal unchecked
execution hook to satisfy an address-stability promise.

### Fee and scale qualification

Report actual paid fee components, execution CPU/memory, referenced bytes, transaction bytes,
minimum ADA, profile and exact compiler/script hashes. Measure ordinary, COSE, transaction
witness, mixed and budget profiles independently; do not extrapolate historical evidence.
Retain the whole-UTxO escape path for large unsolicited native-asset deposits.

Source-level optimizations require compiled positive/adversarial equivalence, malformed shape
rejection, full transaction evaluation and budget ceilings. Never rewrite emitted UPLC or
remove core checks merely to meet a target. Reference-byte reductions are prioritized by
measured contribution. Shared deployment domains/reward sinks require a separate reviewed
factory identity and reward-disposition design; no automatic cross-account deduplication.

Disjoint ordinary spends can reference the same account state. Mutations invalidate stale
state; cumulative policies require consumed state and introduce contention. No TPS figure
or unrestricted enterprise scalability claim is made without concurrent ledger measurements.

## Implementation sequence and acceptance

1. Review this decision with independent contract, economics and wallet reviewers; record
   objections and revisions in the enhancement report before implementation.
2. Implement exact publication/state minimums, authenticated publisher-reference discovery,
   restart-safe hosting hints and explicit missing-reference repair; test stale/mismatched
   hints, historical hosting, fee-selector isolation and replacement-provider recovery.
3. Correct the dashboard ordinary-Spend limit to the SDK/core maximum of eight inputs,
   rejecting unsupported selections before approval; distinguish multi-input sweep from
   the separate single-input whole-value evacuation action. Implement structured cost disclosure and setup funding preflight with exact final-output
   checks. Preserve signing and immutable account/address behavior.
4. Run local checks, frontend verification and disposable DevKit creation/transfer/lifecycle
   tests; preserve all prior evidence and active delayed-recovery/reward workers. Record
   failures and actual economics under a separate enhancement evidence directory.
5. Adopt only source optimizations that pass the same compiled security/budget gates and
   demonstrate savings. If none qualify, retain artifacts and explicitly record the remaining
   fee floor, rather than manufacturing a fee improvement claim.
6. Publish a capability/qualification specification and a remaining-work ledger covering
   durable pre-genesis resume, independent frontend decoding, positive-reward UX, concurrent
   stateful-policy limits, portable deployment manifests and external independent audit.

Production use remains gated by independent audit, full recovery and positive-reward
qualification, reproducible deployments, realistic wallet/device tests and all unresolved
acceptance-ledger items, maximum old/new witness unions and unsupported-address deposit
handling. Existing Phase 0/1 evidence is historical, and Phase 2 is not closed
by this ADR. No DevKit reset, delay reduction or worker restart is permitted for convenience.

## Review disposition

Three independent reviewers challenged the draft before implementation. The contract reviewer
required byte-authenticated durable artifacts, datum-growth gates and the eight-input fix.
The economics reviewer required automatic selector isolation, final-body assertions, complete
mixed setup costs and missing-reference/replacement-sponsor tests. The wallet reviewer required
explicit publisher-key ownership, restart/partial-setup disclosure and truthful quoted costs.
These requirements are incorporated above; their implementation results and remaining gates
are tracked in [the enhancement report](../docs/enhancements/adr-013-review.md).

### Measured optimization disposition

The authorization transfer-dispatch experiment retained full configuration/state/evidence
checks and passed an isolated full check. Applied browser-module bytes fell 15,561→15,451;
mixed-policy bytes fell 15,666→15,548. Measured one-input combined execution fell about
7.6 million CPU and 26 thousand memory. These are modest synthetic improvements, not paid
ledger fees. [Measurements and patch](../docs/enhancements/evidence/auth-dispatch-experiment.json)
are retained for reproduction.

The candidate is deliberately **not adopted** in current artifacts. Historical unfinished
mixed accounts precommit the old final module hash, while resume rederives that final module
from current artifacts. Replacing its template without preserving the old candidate can
strand activation. New setup records now retain candidate identity/bytes, but historical
unpublished candidates still need exact archived-artifact support. This liveness issue takes
priority over a few thousand lovelace of potential savings. A future module release must
prove old unfinished setup resumes before adoption, as well as obtain live fee qualification.

### Next economic milestones

The next fee work should target the dominant recurring charge, rather than repeat small
source cleanups. At the measured parameters, reducing the ordinary transfer reference set
from 36,106 to at most 25,600 bytes is a useful first byte-size milestone (about 29% less).
It is a research target, not an accepted implementation or a guaranteed fee. Evaluate compiler
sharing/lowering against the pinned artifact suite in isolation; adoption requires exact
before/after node-paid fee decomposition, adversarial equivalence, maximum-profile budgets,
legacy unfinished-setup compatibility and a documented module/core migration boundary.

For onboarding at scale, prototype an explicitly versioned shared deployment domain and
reward-sink configuration so identical state/checkpoint/module references and registrations
can be published once. Keep account NFT/asset identity independent and preserve exact hash
verification, reward-receipt disposition and provider replacement. This can amortize operator
capital; it does not eliminate per-transaction reference-script fees or make a shared sponsor
an account authority. Account-specific asset scripts still need an availability strategy.
No shared factory or general policy engine is introduced by the current implementation.

## Numbering after integration

This decision was originally numbered ADR-011 on the PR #6 branch. It was renumbered when
integrating the already-published decisions on `main`. Historical evidence filenames retain
their original branch identifiers; their contents and transaction measurements are unchanged.

## Relationship to the merged shared-core decision

[ADR-012](adr-012-shared-deployment-core-scripts.md) declines shared deployment tiers after
reclaimable references removed the permanent-loss rationale. Its decision remains in force.
The shared-domain research milestone above is not implementation authorization; reconsidering
that decision requires a separate reviewed ADR with operational, registration-race and reward
attribution evidence. Upstream [ADR-011](adr-011-reference-deposit-reclamation.md) also records
219.53 ADA for the raw-Ed25519 profile and 223.29 ADA for the browser profile; these explain
the originally reported cost using a different revision/profile than our initial baseline.
