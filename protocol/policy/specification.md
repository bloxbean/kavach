# Amount tiers, mixed approval and optional periodic budget

Development candidate; not production qualified or independently audited. See
[ADR-007](../../adr/adr-007-tiered-mixed-authorization.md),
[ADR-008](../../adr/adr-008-optional-periodic-budget.md) and the
[acceptance ledger](../../docs/policy/completion-checklist.md).

The [CDDL](schema.cddl) and [independent CBOR vectors](../../conformance/policy/v1.json)
define the candidate encoding. Existing scheme 0, browser schemes 1/2 and historical
immutable scripts are unchanged. Scheme 3 uses `PolicyModule`; scheme 4 uses
`BudgetPolicyModule`. Both take the six common browser module parameters without a mode
parameter. Both are installed through old-admin-authorized module replacement after
ordinary transaction-witness or COSE account creation. They do not implement genesis.

## Authorization

Scheme 3 owns `mixed-configuration`. Scheme 4 owns the mixed payload within
`periodic-configuration`; it validates the enclosing core budget configuration before
accepting any target possession. All operation and possession digests continue to commit
the complete configuration and immutable deployment/account bindings using the existing
typed intent and proof domains. No independent relayer-supplied digest authorizes a spend.

Every credential is fixed to COSE (its ID is in the sorted COSE list) or a Cardano transaction
witness (absent from that list). Ordered distinct evidence cannot change methods or count
a credential twice. COSE evidence uses the existing bounded CIP-8 encoding. Transaction
evidence bytes are empty and the corresponding payment key must be a ledger signatory.
The ordinary fee/collateral wallet is separate unless its key is selected in the policy.

The existing spend role is the strong policy. Small members must be an ordered subset of
strong members. Every sufficient strong subset must also satisfy small approval:
`strongThreshold - (strongMemberCount - smallMemberCount) >= smallThreshold`.
Existing registry uniqueness, admin separation and independent defensive roles still apply
to the entire strong spending set. Configurations remain bounded by 1,024 encoded bytes.

Small approval is selected only for ordinary ADA-only recipient allocations whose total
ADA plus signed maximum account fee is at or below the small-payment threshold. It is an
approval tier, not a hard per-transaction cap. Native-token recipients and whole-UTxO
transfers always use strong approval. Configuration replacement and recovery completion
prove every destination key, including retained keys, in its configured method. Candidate
possession never substitutes for the old module's admin approval.

## Immutable periodic-budget profile

The new profile retains the current state validator, state NFT policy and checkpoint but
uses `BudgetAccountAssetValidator`. Its parameters are the five ordinary asset parameters
followed by `budgetValidatorHash`. `PeriodicBudgetValidator` takes coreVersion,
deploymentDomain, accountId, stateValidatorHash and coreCheckpointHash. The acyclic graph
derives counter custody before the account asset script. This produces a new asset address;
an existing account cannot gain support merely by replacing its authorization module.

Ordinary module configurations and a disabled budget retain ordinary spending rules.
When a periodic configuration enables a positive limit, the immutable asset validator
requires exactly one input carrying its full configured counter NFT at the expected
enterprise counter script address. Only that additional script custody is permitted.
The counter script must execute under Spending and bind to the same immutable checkpoint,
full authenticated reference state, current state version, operation and intent digest.

`PeriodicBudgetNftPolicy(seed, creator, budgetValidatorHash)` consumes a creator-owned
seed with its transaction signature. It mints exactly one empty-name NFT into the counter
script with canonical unused datum `(1,0,0,0)`, positive ADA and no other assets/reference
script. Creating that NFT does not enable a budget. The old admin must install its exact
identity. The demo records verified counter initialization per account alongside its
existing module-profile manifests; these public deployment records are not usage counters.
An arbitrary, unverified NFT is not accepted as a counter by the preparation API.

The full counter NFT identifier, quantity, custody, datum and successor are checked. Its
ADA and NFT value are preserved exactly. There is one successor at the same full address.
Counter deposits remain locked in this development profile, including after disabling or
removing the module. The dashboard uses a 3 ADA counter deposit and discloses this cost.

The debit is **actual account-input ADA minus valid account-change ADA**, including actual
account-paid fees. Core allocation accounting prevents unrelated outputs, sponsor change
or the counter deposit from satisfying account change. Native-token transfers count their
ADA debit; tokens have no exchange-rate valuation. The stronger approval tier never
bypasses an enabled cumulative limit.

Counter usage is updated atomically by the same transaction. Different account inputs can
be spent concurrently when disabled. Enabled budgets serialize on the counter: competing
transactions cannot consume its old UTxO twice. A rebuilt transaction needs new Cardano
transaction witnesses; COSE reuse is valid only if its exact signed intent and window remain
unchanged. The demo prepares a fresh request after stale-input errors.

## Periods and administrative changes

Period 1 is a fixed UTC day (86,400,000 ms), anchored at Unix time zero. Period 2 is a fixed
UTC week (604,800,000 ms), anchored Monday 1970-01-05 (345,600,000 ms). Weekly timestamps
before that anchor are unsupported. These are neither rolling windows nor Cardano epochs.
The entire finite ledger validity interval must lie in one period. Its exclusive upper
bound may equal the period end; an inclusive bound there rejects. Counter time cannot move
backwards. Skipped periods reset on the next accepted spend, without a scheduled job.

Updating only the limit retains recorded usage. Setting limit zero disables enforcement
while retaining the counter identity and period. Re-enabling the same period retains its
recorded usage within the current window; disabled spending is not retroactively counted.
Changing daily/weekly explicitly authorizes a new period counter on the next spend. It is
shown in the signed configuration review. Replacing the budget module with an ordinary
module explicitly removes its budget, under old admin authority. All administrative changes
consume account state and invalidate pending intents at its old version.

The reusable SDK takes chain-derived POSIX validity bounds. The local demo verifies DevKit
magic 42, one-second slots and system-start/tip agreement before mapping slots to time.
It narrows validity at the next reset before requesting signatures. Generic integrations
must use their network's era history rather than adopting DevKit's slot-length assumption.

## Qualification boundaries

Canonical fixtures, compiled scripts and live transaction acceptance are separate evidence.
The demo uses full counter script witnesses so the existing four-reference-input bound is
preserved. Publication, transaction size and combined execution budgets require explicit
testing. Artificial interval tests establish boundary arithmetic, not a week-long live run.
Physical wallet/phone compatibility is distinct from synthetic CIP-30-shaped responses.

## Per-key creation flow

[ADR-009](../../adr/adr-009-per-key-account-creation.md) defines the default dashboard
creation flow. `MixedSetupModule` accepts the existing genesis ABI with the five-field mixed
configuration and every key's configured evidence method. It permits no spending; it allows
only old-admin-approved replacement by its precommitted final PolicyModule with unchanged
configuration. Candidate possession remains mandatory. No new wire encoding or proof scheme
is introduced. The existing full PolicyModule continues to reject genesis.

Creation includes this explicit activation step in one guided sequence. It does not require
users to create all keys under one signing method, and it never asks a transaction-only key
to produce a COSE proof. The backend marks confirmed-but-unactivated accounts `setupPending`
and can resume from their locator plus independently checked public setup deployment record.
