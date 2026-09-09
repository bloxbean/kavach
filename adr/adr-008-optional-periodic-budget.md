# ADR-008: Optional shared daily or weekly ADA budget

Status: Proposed; development candidate implemented, qualification tracked in
[the policy acceptance ledger](../docs/policy/completion-checklist.md). Not production approved.

Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md),
[ADR-005](adr-005-browser-wallet-authentication-and-demo.md),
[ADR-007](adr-007-tiered-mixed-authorization.md).

## Decision

The new core profile supports an optional shared ADA spending budget. Users select
daily or weekly and an ADA limit. It is a hard cumulative limit independent of the
per-transaction small/strong approval tier: stronger spend approval does not bypass it.
Enabling, changing or removing the budget requires the existing administration authority.
Those changes must be explicit signed configuration changes, never a backend preference.

Windows are fixed UTC periods, not rolling durations, Cardano epochs, or periods starting
at account creation. Daily windows begin at 00:00 UTC. Weekly windows begin Monday at
00:00 UTC. Period lengths are 86,400,000 and 604,800,000 POSIX milliseconds. The weekly
alignment anchor is Monday 1970-01-05T00:00:00Z (345,600,000 milliseconds).
For supported ledger timestamps at or after that anchor, the window start is
`anchor + floor((timestamp - anchor) / period) * period`; the daily anchor is zero.
The wire schema must use a bounded period discriminator, not an unrestricted user-supplied
duration or an implementation-dependent enum ordinal.

The transaction builder converts between slots and POSIX time using the selected chain's
era history/system start. It must not assume a fixed slot number per day from slot zero.
On-chain checks use the ledger-supplied validity interval, never the browser clock, backend
clock, submission time or a relayer's claimed current slot.

An accepted transaction's entire finite validity interval must be contained in one budget
window. An exclusive upper bound exactly at the next window boundary is allowed; an
inclusive bound there is not. Infinite, empty, reversed or cross-window intervals reject.
The builder narrows validity to the window boundary before requesting signatures. If that
interval expires, it prepares a fresh intent and fresh approvals. It must not silently alter
an already signed interval or retry the same approved debit in a later window.

## Counter and authorization requirements

The spent amount and its window identifier are authenticated on-chain state. Local storage
and backend storage may cache them but cannot initialize, reset or override them. The first
accepted spend in a later window applies the reset and new debit atomically; there is no
scheduled reset transaction. Skipped empty windows require no state updates. A future-dated
stored counter must not be rolled backwards to authorize a transaction in an earlier window.

All spend paths, including whole-UTxO transfers and native-asset transfers, must account for
the ADA leaving the account. ADA budgets do not assign prices to native tokens. Account
change and external sponsor ADA are not account spending. The canonical debit is account-input ADA minus valid account-change ADA, including actual
account-paid fees. The amount approval tier separately uses recipient ADA plus the signed
maximum account fee. The [wire specification](../protocol/policy/specification.md) defines both.

Each budgeted spend consumes and recreates one authenticated shared counter UTxO. This
serializes budgeted spends: competing transactions cannot both debit the same prior counter.
The SDK must report stale counter conflicts and rebuild against confirmed state. Disabling
the budget restores the profile's ordinary disjoint-input concurrency. Removing or changing
a budget must not permit pending approvals to cross a configuration version change.

The dashboard displays the configured period, remaining ADA, UTC reset time, and the
shared-counter contention tradeoff. Period changes require explicit admin review and start a new period counter at the next
spend. Setting a limit to zero disables enforcement while retaining the counter identity and
period; re-enabling that same period preserves current recorded usage. Counter initialization
uses a creator-bound one-shot NFT. Its deposit remains locked; refund/retirement is unsupported.

## Compatibility and qualification

The current immutable asset rules reject an additional script input and the state rules
reject consuming account state during a transfer. This feature therefore requires a new core
profile and new account address. Existing accounts are not automatically upgraded or moved.
Historical scripts and conformance fixtures remain unchanged.

Qualification requires canonical schema vectors; compiled positive and adversarial tests for
window boundaries, daily/weekly alignment, skipped windows, counter replay, cumulative
overflow, native assets, whole transfers and admin changes; execution-budget coverage; and
full DevKit validation of competing spends and complete enable/change/remove flows. JVM
arithmetic tests and synthetic contexts alone do not establish ledger acceptance.

## Implemented composition

The new immutable component is `BudgetAccountAssetValidator`, parameterized with a per-account
`PeriodicBudgetValidator` hash. Existing state, checkpoint and state-NFT scripts are reused
unchanged. A three-field core envelope carries optional counter identity/period/limit and the
mixed module's authorization payload. Scheme 4 validates that envelope for candidate and
target possession. The asset anchor independently requires counter custody when enabled;
the counter script authenticates full state/NFT identity, exact intent and unique successor.
Non-anchor asset inputs retain exact account/domain/checkpoint/digest binding while the
mandatory anchor performs aggregate checks once. This preserves the existing accounting
partition and avoids duplicating state/counter work for every account input.

Creation starts with an ordinary transaction or COSE module, then installs scheme 4 under
old admin authority. A new counter costs a disclosed 3 ADA locked development deposit. The
counter uses a full script witness to preserve the existing four-reference-input bound.
The demo retains public verified deployment records for counter initialization, just as it
does for module profiles; no backend record authorizes or resets accumulated spending.
