# ADR-012: Shared deployment core scripts

Status: Proposed. Not implemented, not qualified and not approved for production. No contract,
validator, wire schema or acceptance ledger changes accompany this document. The feasibility
gates below are unverified.

Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md),
[ADR-007](adr-007-tiered-mixed-authorization.md),
[ADR-009](adr-009-per-key-account-creation.md),
[ADR-011](adr-011-reference-deposit-reclamation.md).

## Context

Every account setup publishes its own copy of five core scripts, locking about 220 ADA that is
unreachable afterwards. For an operator creating many accounts this is the dominant onboarding
cost and it scales linearly: a thousand accounts lock roughly 220,000 ADA in duplicated script
bytes.

The duplication is not required by the script graph. `AccountDeployment.derive` applies these
parameters:

| script | immutable parameters | varies per account? |
| --- | --- | --- |
| `state` | `coreVersion`, `deploymentDomain` | **no** |
| `checkpoint` | `coreVersion`, `deploymentDomain`, `stateHash`, `coreSink` | only via sink |
| `module` | `moduleVersion`, `abiVersion`, `deploymentDomain`, `stateHash`, `checkpointHash`, `moduleSink` | only via sink |
| `nft` | `coreVersion`, `deploymentDomain`, `seed`, `creator`, `stateHash` | **yes** — one-shot seed |
| `asset` | `coreVersion`, `deploymentDomain`, `accountId`, `stateHash`, `checkpointHash` | **yes** — account id |

`AccountStateValidator` takes only `coreVersion` and `deploymentDomain` and nothing per-account.
Accounts get distinct state validators today because the dashboard generates a fresh random
32-byte `deploymentId` per account. That is a demo choice, not a protocol requirement: ADR-001
describes the deployment domain as an immutable discriminator chosen before initialization, which
is a property of a *deployment*, not of an account.

Account identity does not depend on that fragmentation. `AccountLib.resolveFrom` selects state by
exact `stateRef`, and `authenticateFrom` requires the singleton account NFT and an exact canonical
domain match. Neither locates "the UTxO at this address". `StateTransitionLib.sponsorInputs`
separately forbids any non-state script input, so one account's mutation cannot consume another's
state even when both sit at the same address.

## Decision

Adopt a shared deployment core in tiers, so that operators pay the core publication cost once per
deployment rather than once per account. Each tier is a separate decision with its own gate; later
tiers are not approved by adopting earlier ones.

### Tier A — shared state validator

Fix `deploymentId` per deployment instead of generating it per account. Publish the state
validator once. Every account in the domain shares that script and address, remaining
distinguished by its own state NFT and `stateRef`.

Saves 35.09 ADA per account after the first; about 184 ADA remains.

This tier requires no contract change — only that callers stop randomizing the discriminator —
which is what makes it the credible first step and also why its gate is about adversarial
verification rather than design.

### Tier B — shared checkpoint and authorization module

Additionally share `checkpoint` and `module`, reducing per-account publication to `nft` and
`asset` alone: about 73 ADA, saving roughly 146 ADA per account.

**This tier is blocked on reward-sink custody and is not approved by this ADR.** Both scripts take
an immutable reward sink as a script parameter, and `AccountLib.ownSink` requires every reward
receipt for that credential to pay that exact address. The sink is immutable at the script-hash
level precisely so that reward destinations cannot be redirected. Sharing the script therefore
means one key address receives the staking rewards of every account in the domain — a custody
regression an operator cannot accept on behalf of its users.

Making Tier B viable requires relocating the reward sink from a script parameter to a per-account
value carried in authenticated state, which weakens an existing hash-level binding into a datum
check. That is a separate security decision and needs its own ADR; it must not be folded in as an
implementation detail.

### Tier C — unparameterized identity scripts

Out of scope for V1 and recorded only to close the question. Sharing `nft` and `asset` would
require moving account identity out of script parameters and into datum validation. ADR-001
requires authenticating state using the full state NFT asset identifier, quantity and expected
validator; today the expected-validator half is enforced by parameterization. Relocating it into
datum checks removes a structural guarantee in exchange for the last ~73 ADA. The trade is not
worth it and is not proposed.

### Pluggable authorization is unaffected

Signing and policy modules are already per-account and already replaceable. An account records its
module as `AuthModuleRef(scriptHash, version)` in authenticated state and changes it through the
`ReplaceModule` action under the old configuration's admin policy (ADR-007, ADR-009). Sharing the
core does not touch that boundary: each account still names its own module hash, and authorization
still binds to account, operation, state version and intent digest.

Sharing in fact improves module availability. A newly published authorization module becomes
usable by every account in the domain without that account paying a fresh publication, so adding a
future signing method is a one-time cost for the deployment rather than a per-account one. Under
Tier A the module still binds to the shared `stateHash` and its own sink; the sink constraint
described in Tier B applies equally to sharing modules and is the same unresolved question.

## Feasibility gates

None are verified.

1. Adversarial tests confirm that with several accounts' state UTxOs at one shared address, no
   transaction can consume, reference or satisfy itself against the wrong account's state — in
   compiled UPLC, with full ledger validation, not JVM models.
2. Transaction construction and UTxO selection remain correct when the state address holds many
   accounts' states and many reference outputs. Off-chain code that assumes a lightly populated
   state address is in scope.

   A reading of the current singleton assumptions found none that break under sharing, which is
   the basis for the "no contract change" claim above and must be re-checked rather than trusted:
   `StateTransitionLib.outputs` counts outputs carrying the account's own NFT policy and requires
   exactly one, not outputs at the state address; `AccountLib.stateOutput` is a per-output
   predicate asserting the destination address, this account's NFT and a matching datum;
   `AccountLib.resolveFrom` and `authenticateFrom` select by exact `stateRef` and singleton NFT;
   and off-chain, `AccountLocator.restore` resolves through `StateProvider.find(address, unit)`,
   which queries by address *and* asset unit and requests two entries specifically to reject an
   ambiguous claim rather than taking the first.
3. Execution budgets are unchanged. Sharing alters neither script bytes nor validator logic in
   Tier A, but this must be measured rather than assumed.
4. Reference-script fee behaviour is measured for a shared reference read by many concurrent
   transactions.
5. Tier B is not attempted before the reward-sink question has its own accepted ADR.

## Consequences

- Per-account onboarding cost falls from about 220 ADA to about 184 ADA under Tier A, and could
  fall to about 73 ADA under Tier B if and only if reward-sink custody is solved.
- **Blast radius grows.** Today a defect in one account's core affects one account. A shared core
  means one defect affects every account in the domain. This is the honest counterweight to the
  saving and it argues for smaller, well-audited deployment domains rather than one global domain.
- Existing accounts cannot migrate. They hold per-account domains, and per ADR-001 core upgrades do
  not preserve addresses. Sharing applies to new deployments only.
- A deployment domain becomes a meaningful operational unit — an enterprise boundary, a tenant, an
  audit scope — rather than a per-account random value. Choosing its granularity becomes a
  deployment decision with security consequences.
- Compiled script size remains the underlying cost driver at `4310 × (bytes + 160)`. Sharing
  amortizes it; it does not reduce it. Reducing emitted script size stays independently valuable
  and is the only lever that helps every tier at once.

## Relationship to ADR-011

These proposals constrain each other and must be decided together. [ADR-011](adr-011-reference-deposit-reclamation.md)
lets a closed account reclaim its deposits under the invariant that no live account depends on the
reference being spent. Sharing makes that invariant unsatisfiable in practice, because a shared
reference is depended on by every account in the domain for as long as any of them is live.

The coherent combination is tiered: shared scripts are published once and permanently, while
genuinely per-account scripts keep a reclaim path. On today's graph that means `state` — and under
Tier B also `checkpoint` and `module` — are permanent, and `nft` and `asset` remain reclaimable on
closure. Adopting sharing without recording this makes ADR-011's stated benefit unachievable.
