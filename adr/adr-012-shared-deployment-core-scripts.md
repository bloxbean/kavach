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

Figures throughout describe the `Ed25519Module` script graph, measured in
[ADR-011](adr-011-reference-deposit-reclamation.md) at 219.53 ADA. The dashboard's browser and
mixed graphs settle at 223.29 ADA for the same five references because applied parameters differ,
so every total here is approximate and script-size dependent rather than fixed.

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

Sharing converts part of the cost from recurring to one-time. It does not remove the rest, and the
remainder stays permanent per account:

| tier | published once per domain | **still locked per account** | per 1,000 accounts |
| --- | ---: | ---: | ---: |
| today | — | 219.53 ADA | 219,530 ADA |
| Tier A | 35.09 ADA | **184.44 ADA** | 184,440 ADA |
| Tier B | 146.26 ADA | **73.26 ADA** | 73,260 ADA |
| Tier C (declined) | 219.53 ADA | ~0 | ~0 |

Tier A therefore leaves 84% of today's cost recurring. Accepting that shared deposits are
permanent is a reasonable trade for the amortized slice, and it is the basis for not extending
[ADR-011](adr-011-reference-deposit-reclamation.md) to shared scripts. It is not a reason to treat
the per-account remainder as settled: that remainder is exactly what ADR-011 addresses, and it is
the larger number under every tier that is not declined.

### Tier A — shared state validator

Fix `deploymentId` per deployment instead of generating it per account. Publish the state
validator once. Every account in the domain shares that script and address, remaining
distinguished by its own state NFT and `stateRef`.

Saves 35.09 ADA per account after the first; about 184 ADA remains.

**Normative precondition: accounts must keep a per-account discriminator that survives the fixed
`deploymentId`.** The random discriminator is not only what separates the state validator today, it
is what separates `checkpoint` and `module`. Those are parameterized by
`(coreVersion, deploymentDomain, stateHash, sink)` and `(versions, deploymentDomain, stateHash,
checkpointHash, sink)`, so once the domain and state hash are constants the sink is the only
remaining discriminator — and the dashboard passes the same sponsor address as both `coreSink` and
`moduleSink`. Fixing the domain without changing that makes any two accounts from one sponsor derive
**identical checkpoint and module hashes**, silently reaching Tier B — which this ADR does not
approve — as a side effect of Tier A.

The immediate failure is not subtle: creation registers the checkpoint and module reward accounts
unconditionally, so the second account submits a stake registration for an already-registered
credential and is rejected. `CoreCheckpoint.certify` accepts only registration, so there is no
deregistration path and no recovery from the collision.

Tier A therefore requires distinct per-account sinks, or reinstating a per-account parameter on the
checkpoint and module, before the discriminator is fixed. This is a design change, not merely
"stop randomizing", and it is the reason the tier is not as free as its script parameters suggest.

### Tier B — shared checkpoint and authorization module

Additionally share `checkpoint` and `module`, reducing per-account publication to `nft` and
`asset` alone: about 73 ADA, saving roughly 146 ADA per account.

**This tier is blocked on pooled reward accounting and is not approved by this ADR.**

The visible half is custody. Both scripts take an immutable reward sink as a script parameter, and
`AccountLib.ownSink` requires every reward receipt for that credential to pay that exact address.
Sharing the script means one key address receives the staking rewards of every account in the
domain.

The blocking half is worse and is not fixed by moving the sink. A shared script hash is a **shared
stake credential**, and therefore a single pooled reward balance. The ledger requires a withdrawal
to take the entire balance, and `AccountLib.receipts` binds one receipt output per positive
withdrawal. Rewards from different accounts are not separable within that pool. If the sink were
relocated into authenticated state, whichever account transacts first would withdraw the whole
pooled balance to its own declared sink — one account draining another's rewards. AGENTS.md already
warns never to run two spend workers against the same reward balances; sharing the credential makes
that condition structural rather than operational.

Tier B therefore needs per-account reward attribution, not merely a per-account sink. Recording
this distinction is the point of the tier being written down: a future ADR that relocates the sink
into datum and declares Tier B unblocked would be wrong.

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

Module sharing is a **Tier B** benefit and is not delivered by Tier A. The module is
parameterized by the checkpoint hash, which is itself parameterized by the sink, so with distinct
per-account sinks — required by Tier A's precondition above — each account still derives and
publishes its own module. Once both sinks are shared, a newly published authorization module
becomes usable by every account in the domain without that account paying a fresh publication, so
adding a future signing method becomes a one-time cost for the deployment. That is the enterprise
benefit, and it arrives only with Tier B and its unresolved reward-attribution problem.

## Feasibility gates

None are verified.

1. Adversarial tests confirm that with several accounts' state UTxOs at one shared address, no
   transaction can consume, reference or satisfy itself against the wrong account's state — in
   compiled UPLC, with full ledger validation, not JVM models.
2. Transaction construction and UTxO selection remain correct when the state address holds many
   accounts' states and many reference outputs. Off-chain code that assumes a lightly populated
   state address is in scope.

   The on-chain state-transition path was read and no address-keyed assumption was found:
   `StateTransitionLib.outputs` counts outputs carrying the account's own NFT policy and requires
   exactly one, not outputs at the state address; `AccountLib.stateOutput` is a per-output
   predicate asserting the destination address, this account's NFT and a matching datum;
   `AccountLib.resolveFrom` and `authenticateFrom` select by exact `stateRef` and singleton NFT.
   Off-chain, `AccountLocator.restore` is likewise safe: its provider queries by address *and*
   asset unit and requests two entries specifically to reject an ambiguous claim. All of this is
   source-verified only and not ledger-tested.

   **The off-chain reference-discovery path is not safe and an earlier revision of this ADR wrongly
   claimed otherwise.** `DemoService.build` resolves every required reference by scanning
   `utxos(holder)` — the whole state address — for a matching `getReferenceScriptHash()`, and
   `utxos` pages 100 at a time to a hard limit, throwing beyond 10,000 outputs. Under sharing that
   address accumulates roughly five outputs per account, so every transaction build for every
   account becomes an O(N) paginated scan of the entire domain, and organic growth alone fails the
   domain at around two thousand accounts. Resolution must move to a deployment manifest recording
   each publication's exact `TxOutRef`, read directly rather than discovered by enumeration. The
   same pattern appears in the mixed-setup and budget-counter lookups.

   Separately, the saving is not realized by current code: setup publishes all five scripts
   unconditionally with no existence check, and adding one introduces a concurrent-creation race in
   which two creations both observe "not published" and both pay. Publish-once needs its own
   coordination rule.
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
- **Availability becomes a domain-wide target.** Anyone can pay to a script address, and a
  datum-less, NFT-less output there is permanently unspendable, so junk accumulates and cannot be
  cleared. An attacker who pushes the shared address past the discovery limit breaks transaction
  building for every account at once, where today the same act degrades one account. This is more
  immediately exploitable than the code-defect blast radius, and gate 2's manifest-based resolution
  is its mitigation as well as its performance fix.
- **Domain membership is permissionless, so a "tenant" is a convention and not a boundary.**
  `StateNftPolicy` gates creation only on the creator's own seed and signature; nothing restricts
  who may derive scripts under an existing `deploymentId`, which is public in every state datum and
  every locator backup. A third party can therefore join a domain and, by choosing a matching sink,
  deliberately collide onto another account's checkpoint and module. Treating a deployment domain
  as an audit or tenant scope is an off-chain convention with no on-chain enforcement, and Tier A's
  precondition is what keeps a collision from being reachable.
- **Accounts in a domain become publicly enumerable and linkable.** One address scan yields the
  full account roster, and each state datum discloses that account's asset validator — the address
  holding its funds — so an operator also discloses customer count and growth rate. Unlinkability
  is not a stated protocol goal and on-chain configuration is already public, so this is accepted
  rather than a regression, but it is a material change from one address per account and is
  recorded here deliberately.
- Existing accounts cannot migrate. They hold per-account domains, and per ADR-001 core upgrades do
  not preserve addresses. Sharing applies to new deployments only.
- A deployment domain becomes a meaningful operational unit — an enterprise boundary, a tenant, an
  audit scope — rather than a per-account random value. Choosing its granularity becomes a
  deployment decision with security consequences.
- Compiled script size remains the underlying cost driver at `4310 × (serialized output bytes + 160)`. Sharing
  amortizes it; it does not reduce it. Reducing emitted script size stays independently valuable
  and is the only lever that helps every tier at once.

## Relationship to ADR-011

These proposals constrain each other and must be decided together. [ADR-011](adr-011-reference-deposit-reclamation.md)
lets a closed account reclaim its deposits under the invariant that no live account depends on the
reference being spent. Sharing makes that invariant unsatisfiable in practice, because a shared
reference is depended on by every account in the domain for as long as any of them is live.

ADR-011's accepted decision — publish references to a reclaimable holder rather than to the sealed
state validator — is largely independent of this one, and composes with it. The coherent
combination is tiered: shared scripts are published once to an address nobody can spend, accepting
permanence for the amortized slice, while genuinely per-account scripts are published to a
reclaimable holder. Under Tier A that makes `state` permanent and the other four reclaimable; under
Tier B `checkpoint` and `module` join the permanent set. The reason shared publications should stay
permanent is liveness rather than cost: a shared publication is a shared dependency, and whoever
could spend it could degrade every account in the domain until it was republished.
