# ADR-011: Reference deposit reclamation on account closure

Status: Proposed. Not implemented, not qualified and not approved for production. No contract,
validator, wire schema or acceptance ledger changes accompany this document. The feasibility
gates below are unverified.

Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md),
[ADR-009](adr-009-per-key-account-creation.md),
[ADR-012](adr-012-shared-deployment-core-scripts.md).

## Context

Account setup publishes each core script as a reference output at the sealed state validator's
enterprise address with no datum. Publications now lock the ledger minimum for each script rather
than a flat 80 ADA, measured on DevKit as:

| script | bytes | locked |
| --- | ---: | ---: |
| state | 7,935 | 35.09 ADA |
| checkpoint | 10,615 | 46.64 ADA |
| module | 14,766 | 64.53 ADA |
| nft | 5,865 | 26.17 ADA |
| asset | 10,719 | 47.09 ADA |
| **total** | **49,900** | **219.53 ADA** |

That minimum is `4310 × (serialized output bytes + 160)`, so the figure is set almost entirely by
compiled script size and cannot be reduced further by transaction construction. The remaining
cost is structural: the ADA is not merely idle, it is unreachable.

It is unreachable by construction, not by omission. `AccountStateValidator.validate` has one
entrypoint, which resolves a previous `AccountState` by exact `stateRef`, authenticates the
singleton state NFT and requires `StateTransitionLib.sponsorInputs` to hold — and that helper
requires every non-state input to be a plain ADA-only key input. A datum-less, NFT-less reference
output can be neither the resolved state nor a permitted additional script input, so no
transaction can spend it under any redeemer.

## Decision

Introduce a terminal account-closure lifecycle and a reclaim spending branch, so that a
deliberately closed account returns its reference deposits instead of burning them.

This is two decisions, and the first is the larger one:

1. **A terminal `Closed` account mode and a `Close` action.** Neither exists. `AccountMode`
   permits `Normal`, `Frozen` and `RecoveryPending`; `Action` permits `Spend`, `ReplaceConfig`,
   `ReplaceModule`, `Freeze`, `Unfreeze`, `StartRecovery`, `CancelRecovery` and
   `CompleteRecovery`. Closure must be an authorized, irreversible state transition that burns
   the account's state NFT and asserts no remaining account-held value.
2. **A reclaim branch in the immutable state validator**, contingent on (1), permitting a
   datum-less reference output at the state address to be spent only when its account is
   provably closed in the same transaction.

### What this cannot do

**It cannot recover ADA already locked.** The reclaim branch changes immutable core code, which
changes the state validator's script hash and therefore its address. Per ADR-001, core upgrades do
not preserve addresses. Deposits published under today's core remain permanently locked at the old
address. This ADR benefits future deployments only, and must not be presented as a recovery of
existing funds.

### Governing invariant

> A reference output may be spent only when no live account depends on it.

Under today's per-account references that reads as "this account is closed". The dependency is
real and not merely conventional: `AccountDeployment.restore` retrieves all five scripts by hash
from the chain indexer, which is how an account is recovered on a clean device with only its
locator. Stripping a live account's references removes its recovery path. That is the adversarial
case this design exists to prevent, and it is more damaging than the ADA it recovers.

### Authorization and destination

Closure must be authorized by the configuration's admin policy under the same signed intent
encoding, deployment domain binding and state-version replay protection as every other mutation.
Spending authority must not imply closure authority.

The reclaimed ADA pays the immutable `coreSink` address recorded in the account's core binding,
not an address supplied in the redeemer. The sink is already an immutable full key address fixed
at deployment and already the enforced destination for reward receipts; reusing it means closure
introduces no new caller-chosen payment destination and no new front-running surface. A
redeemer-supplied destination would let a relayer redirect the refund and is rejected.

The sink is per-creator today, so the refund reaches whoever deployed the account. That is the
right answer while references stay per-account, and the wrong one under
[ADR-012](adr-012-shared-deployment-core-scripts.md) Tier B, where a shared checkpoint means a
shared sink and every refund in the domain would reach the operator's key rather than the account
owner's. Acceptable for an enterprise tenant, not for a consumer deployment.

### Transaction size

Reclaim must **reference** the state script, not attach it. The reference outputs themselves cost
no body bytes when spent, since a spent input's `scriptRef` lives in the resolved output rather
than the transaction body. The witness does not: `AccountMutation` attaches the state validator
inline today, which is 7,935 bytes before the redeemer, against a 16,384-byte bound that already
forced `MixedSetupModule` to exist (ADR-009). A reclaim transaction that also burns the state NFT
and spends up to five reference inputs is unlikely to fit if the state script is attached. This is
a design constraint on the implementation, not an open question.

### Adversarial cases the design must reject

- Spending any reference output of an account that is not closed in the same transaction.
- Spending the state UTxO, the asset address or any account-held native asset through the reclaim
  branch. The branch must key on absence of the state NFT and absence of a datum, and must not
  accept a datum-bearing output.
- Closing an account that still holds value, which would strand it at an address with no
  remaining spending path.
- Closing an account in `RecoveryPending`, which would let a spend-authority holder discard a
  pending recovery. Closure from `Frozen` and from `RecoveryPending` must be decided explicitly.
- Replay of a closure intent against a different account, state version or deployment domain.
- Partial closure: burning the NFT without reclaiming, or reclaiming a subset of references,
  leaving an account that `restore` can no longer reconstruct but that still holds value.

### Scope note

The `nft` reference (26.17 ADA) is already functionally dead after genesis. `StateNftPolicy` is a
one-shot policy bound to a consumed seed UTxO and can never mint again, yet its publication is
still required because `restore` fetches it by hash. The same reclaim path covers it; no separate
mechanism is warranted.

## Feasibility gates

None of these are verified. Each must pass before this ADR advances beyond Proposed.

1. A reclaim branch fits within the state validator's execution budget and the 16,384-byte
   publication bound that already forced `MixedSetupModule` to exist (ADR-009).
2. Closure, NFT burning and multi-reference reclamation fit in one transaction, or the design
   tolerates a partially reclaimed account without violating the governing invariant.
3. Adversarial rejection cases above are expressed as compiled UPLC tests, not JVM helpers.
4. Full ledger validation on DevKit confirms both acceptance of a legitimate closure and rejection
   of every adversarial case.
5. The interaction with [ADR-012](adr-012-shared-deployment-core-scripts.md) is resolved — see
   below.

## Relationship to ADR-012

These two proposals are a trade-off, not a stack. ADR-012 reduces per-account reference cost by
sharing core scripts across every account in a deployment domain. Under sharing, the governing
invariant above — "no live account depends on it" — is satisfied only when no account in the
entire domain is live, which for a shared deployment is effectively never. Sharing therefore makes
the deposits amortized-cheap and permanently unreclaimable, while this ADR makes them reclaimable
but only while references stay per-account.

Adopting both is possible only if reference publication is explicitly tiered: shared scripts are
published once and never reclaimed, while any genuinely per-account script retains a reclaim path.
On today's script graph that leaves `nft` and `asset` reclaimable and the rest permanent. The two
ADRs must be decided together, and whichever is adopted first constrains the other.

## Consequences

- Future accounts recover roughly 220 ADA on deliberate closure, subject to the tiering above.
- The immutable core gains a spending branch at the address holding every account's state. This is
  the most security-sensitive surface in the protocol and the reason the gates above are strict.
- Account closure becomes a first-class protocol concept with its own authorization policy,
  approval flow and recovery interaction, which is a larger change than the deposit refund that
  motivates it.
- Accounts created before this change keep their deposits locked permanently.
