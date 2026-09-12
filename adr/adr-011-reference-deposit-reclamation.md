# ADR-011: Reference deposit reclamation

Status: Proposed. Not implemented, not qualified and not approved for production. No contract,
validator, wire schema or acceptance ledger changes accompany this document. The feasibility gates
below are unverified.

Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md),
[ADR-009](adr-009-per-key-account-creation.md),
[ADR-012](adr-012-shared-deployment-core-scripts.md).

## Context

Account setup publishes each core script as a reference output at the sealed state validator's
enterprise address with no datum. Publications now lock the ledger minimum for each script rather
than a flat 80 ADA:

| script | script bytes | serialized output bytes | locked |
| --- | ---: | ---: | ---: |
| state | 7,935 | 7,982 | 35.09 ADA |
| checkpoint | 10,615 | 10,662 | 46.64 ADA |
| module | 14,766 | 14,813 | 64.53 ADA |
| nft | 5,865 | 5,912 | 26.17 ADA |
| asset | 10,719 | 10,766 | 47.09 ADA |
| **total** | **49,900** | **50,135** | **219.53 ADA** |

The minimum is `4310 × (serialized output bytes + 160)`; the output adds 47 bytes of address and
CBOR framing to the script, which is why the figure is not reproducible from the script column
alone. These values are computed by `MinAdaCalculator` at DevKit's `coins_per_utxo_size` of 4310
and recorded in `build/phase2/reference-minimums.json`. The deposits were also read back from the
DevKit ledger after publication and are recorded with their transaction ids in
[the deposit evidence](../docs/phase2/evidence/reference-deposits-2026-09-12.json). The table
describes the `Ed25519Module` script graph; the dashboard's browser graph settles at 223.29 ADA for
the same five references, because applied parameters differ. This is DevKit evidence, not a
production deployment or audit claim.

The cost is structural rather than wasteful-by-omission: the ADA is unreachable.
`AccountStateValidator.validate` has one entrypoint, which resolves a previous `AccountState` by
exact `stateRef`, authenticates the singleton state NFT and requires
`StateTransitionLib.sponsorInputs` to hold — and that helper requires every non-state input to be a
plain ADA-only key input. A datum-less, NFT-less reference output can be neither the resolved state
nor a permitted additional script input, so no transaction can spend it under any redeemer.

## Decision

**Publish reference scripts to a reclaimable holder address instead of the sealed state validator.
Do not add a reclaim branch to the immutable core.**

Nothing in the protocol requires reference publications to live at the state validator's address.
The ledger resolves a reference script by hash, not by location. `AccountDeployment.restore`
fetches each script by hash from the chain indexer, which is location-independent and survives the
output being spent. No validator in the graph inspects the address of a reference output: every
`referenceScript()` check in the contracts is a *negative* check asserting that some output does
**not** carry a reference script. The only binding is off-chain and self-imposed —
`DemoService.build` filters `utxos(holder)` for a matching `getReferenceScriptHash()` purely
because that is where the publication flow happens to put them.

Publishing instead to a key-controlled holder makes the whole deposit reclaimable by an ordinary
spend, with:

- no change to immutable core code, and therefore no new script hash and no address migration;
- no `Close` action, no terminal lifecycle mode and no wire-schema change;
- no state-NFT burn path;
- no new spending branch on the address that holds every account's state;
- no interaction with recovery, freeze or admin authority;
- reclamation decoupled from account lifecycle entirely — references can be reclaimed and
  republished independently, including for a live account.

### Residual risk this accepts

Spending a published reference makes transactions that read it unbuildable until it is republished.
This is a liveness cost, not a custody one: the script bytes are public, recoverable from the
indexer by hash, and republication is permissionless, so any party can restore availability.
Account funds and authorization are unaffected, because no validator consults the publication's
location or existence — only transaction construction does.

That risk must still be bounded deliberately. The holder should be an address the account operator
controls and does not sweep, and the creation flow should verify each required reference resolves
before building. Under [ADR-012](adr-012-shared-deployment-core-scripts.md) sharing, a shared
publication becomes a shared liveness dependency, and whoever holds its key can degrade every
account in the domain until republication — which argues for shared publications to be placed at an
address nobody can spend, accepting permanence for exactly those.

### Feasibility gates

1. Reference scripts at a key-controlled address are accepted by the ledger as reference inputs for
   spending, minting and rewarding executions, verified on DevKit rather than assumed.
2. Off-chain reference discovery moves off address enumeration. `DemoService.build` currently scans
   `utxos(holder)`, which is the only thing tying publications to the state address; resolution
   should use a deployment manifest recording each publication's exact `TxOutRef`.
3. Republication after a reclaim reproduces byte-identical scripts and therefore identical hashes,
   so an account's bindings stay valid.
4. A reclaim that races a transaction reading the same reference fails the reader loudly rather
   than producing an unauthorized outcome.

## Rejected alternative: closure-based reclaim from the state address

The obvious design — a terminal `Closed` mode plus a reclaim branch in the state validator — was
examined and rejected. It is recorded here because it is the design most likely to be proposed
again, and each of the following is a separate reason it fails.

**It cannot recover ADA already locked.** A reclaim branch changes immutable core code, changing
the state validator's hash and therefore its address. Per ADR-001, core upgrades do not preserve
addresses. Deposits published under today's core stay locked at the old address regardless. This is
equally true of the accepted decision above, and is the one property no design can fix.

**Closure would strictly dominate recovery.** Every mutation consumes the same state UTxO, so a
`Close` transaction and a `StartRecovery` transaction are direct competitors for it. A `Close`
landing one block earlier destroys the recovery path permanently, and no ordering rule prevents
that — the same honesty ADR-001 already applies to freeze, and which AGENTS.md requires. Forbidding
closure from `RecoveryPending` buys nothing, and is in any case vacuous because `CancelRecovery`
moves `RecoveryPending → Frozen`. An admin-authorized, instantly irreversible action that removes
recovery is precisely the "disable recovery" capability ADR-001 §9 lists as unsupported in V1 and
requiring its own ADR. Closure authority would be strictly stronger than admin authority, and would
have to be timelocked by at least the immutable recovery delay to be safe — at which point it is no
longer a small addition.

**The state validator cannot read the reclaim destination.** The obvious destination is the
immutable reward sink, but `CoreBinding` is three script hashes and `AccountState` carries no sink.
The sink exists only as a `CoreCheckpoint` script parameter, baked into that script's hash. A
reclaim branch in the state validator therefore cannot observe it, and would have to bind the
destination through the checkpoint's invocation or relocate the sink into authenticated state —
the same hash-to-datum weakening ADR-012 defers to a separate ADR. The sink is also fixed at
derivation while account control legitimately changes through `ReplaceConfig`, `ReplaceModule` and
`CompleteRecovery`, so after a successful recovery the refund would reach the original creator,
possibly the party recovered against. In the dashboard today that address is the sponsor, not the
owner.

**Burning the state NFT is not possible, and not compatible with a `Closed` mode.**
`StateNftPolicy` mints exactly `+1` and has no burn path; its own documentation records that
burning is unsupported. `StateTransitionLib.authenticate` additionally requires an empty mint field
on every mutation, and `StateTransitionLib.outputs` requires exactly one NFT-bearing output. The
two halves of the design also contradict each other: burning the NFT leaves no state UTxO and
therefore no `Closed` datum, while keeping a `Closed` UTxO leaves a permanent standing
authorization reusable for unlimited future reclaims.

**Reclaim at a shared address cannot identify its account.** Reference outputs are datum-less and
carry no account binding. Under a per-account `deploymentId` the address happens to hold one
account's publications, but ADR-012 records that per-account randomization is a dashboard choice
rather than a protocol requirement. Where accounts co-reside, "spend this reference because its
account is closed" has no on-chain referent, and closing one account would authorize spending a
live account's publications. Only `nft` and `asset` are genuinely per-account; `state`, `checkpoint`
and `module` are shared by construction, and `authModule` is caller-chosen through `ReplaceModule`.

**Double satisfaction is hard to exclude.** Spending five reference outputs produces five separate
`Spending` executions of the state validator. A naive per-execution check that "some output pays
the sink at least my value" is satisfied for all five by one output. The sink is also already the
enforced destination for reward receipts, so a reclaim output and a receipt are the same shape and
one output could satisfy both. Excluding this needs the explicit output-index disjointness
machinery the codebase already carries for receipts, and budget analysis would have to cover six
executions of a 7,935-byte validator in one transaction against a per-transaction limit.

**"Assert no remaining account value" is unimplementable.** A validator sees only the transaction's
inputs and reference inputs; there is no global UTxO view, and account assets live at a different
address that `sponsorInputs` forbids consuming. After a burn, anything left at the asset address is
permanently unspendable, so closure converts a racing inbound payment into total loss.

**Closure strands more than it recovers, and the balance sheet is incomplete.** There is no
deregistration path — `CoreCheckpoint.certify` accepts only registration — so both stake deposits
stay locked, which ADR-001 already records as unrefundable in V1. Both reward credentials remain
registered and can still receive credits afterwards, but withdrawal requires state authentication
and therefore the NFT, so any post-closure reward is stranded permanently.

**The wire-schema impact is larger than the refund.** A `Closed` mode needs a new mode tag,
`LifecycleLib.stateShape`'s exhaustive switch extended, and a `schemaVersion` decision. A `Close`
action must be appended at constructor index **9**: `Action` permits nine variants, not eight, and
index 8 is already `TransferWholeUtxo`. Placing `Close` at 8 would silently collide and change the
meaning of every previously signed intent. All of this is normative, language-independent wire
schema requiring conformance fixtures.

## Relationship to ADR-012

The accepted decision here is largely independent of ADR-012, which is the main reason to prefer
it: moving publications off the state address does not depend on how many accounts share that
address. The rejected design was tightly coupled to it, because a shared address makes per-account
reclaim inexpressible.

One interaction remains. ADR-012 reduces cost by publishing shared scripts once; this ADR reduces
it by making publications spendable. They compose — a shared publication can also sit at a
reclaimable holder — but a shared publication is a shared liveness dependency, so the operator
holding its key can degrade the whole domain. For shared scripts, permanence is the safer choice
and the amortized cost makes it acceptable; for genuinely per-account scripts, reclaimability is
worth more than permanence. That split is the coherent combination, and it is available without
touching immutable core.

## Consequences

- Future accounts can recover their per-account reference deposits by an ordinary spend, with no
  protocol change. Under today's per-account publication that is the full 219.53 ADA; under
  ADR-012 Tier A it is 184.44 ADA, and under Tier B 73.26 ADA, the shared remainder being
  deliberately permanent.
- Accounts created before this change keep their deposits locked permanently, because their
  publications already sit at an address with no spending path.
- Reference availability becomes an operational responsibility rather than a structural guarantee.
  This is the real cost of the decision and the reason gates 2 and 4 exist.
- The immutable core is untouched, so no adversarial contract test, execution-budget measurement,
  address migration or conformance fixture is required.
