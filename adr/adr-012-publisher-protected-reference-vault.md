# ADR-012: Publisher-protected reference-script vault

Status: Accepted implementation direction after three internal reviews, 2026-09-19.
Scoped development implementation and targeted qualification complete; no production approval.
See the [qualification report](../docs/enhancements/adr-012-review.md) for evidence and limitations.

Related: [architecture](adr-001-kavach-programmable-smart-account-architecture.md),
[reference economics](adr-011-economics-extensibility-and-wallet-safety.md),
[deployment specification](../protocol/browser/deployment-economics.md).

## Problem

ADR-011 introduces reclaimable reference outputs at the connected fee wallet's key address.
Kavach excludes these outputs from its funding and collateral selection, but other wallet
software may select them as ordinary funds. Accidental removal interrupts account operations
until an exact reference copy is available. The publisher should retain intentional reclamation
without leaving references in its ordinary wallet UTxO pool.

## Decision

Publish new references at a dedicated, small JuLC Plutus V3 spending-validator address.
Its immutable parameters bind the publisher payment-key hash, the ASCII purpose
`kavach-acc-ref`, and the full account identifier (state-NFT policy ID and asset-name bytes).
Use a versioned canonical encoding, documented independently of Java field order. One account
and publisher can use the same vault address for multiple reference-script outputs. The
publisher may be a separate wallet from any account authority; initially the connected fee
wallet is the publisher. No private keys or new derivation paths are managed by Kavach.

The vault is a separate custody script, not an account module and not a new account core.
Its payment credential protects the capital. The reference-script field contains the actual
account script being hosted; it is not the vault spending script. Referencing an output does
not spend it or invoke its custody validator. Ordinary account transactions therefore require
no publisher signature and do not run the vault validator. Current account addresses, state,
module configuration, V1 encodings and existing contract artifacts remain unchanged.

## Authorization and wire contract

Only the spending purpose is accepted. Reclamation requires the exact configured publisher
payment-key hash in transaction signatories and an explicit, strictly validated versioned
Reclaim redeemer. A label or datum is never sufficient authorization. Fixed purpose and
full account identity are committed in the applied script, not merely transaction metadata.
Reject malformed parameters, wrong purpose, unsupported redeemer variants/versions, missing
publisher signature and a different publisher's signature. The ledger verifies required
signatories against transaction witnesses; a fabricated context alone is not a signed transaction.

The publisher intentionally retains the ability to remove any or all copies, including
active references. No account-holder approval, replacement-copy proof, timelock or permanent
lock is imposed. Ordinary key-wallet coin selection cannot spend this script output with
only a payment signature: a purpose-built script-spending transaction is required. This is
isolation from ordinary accidental selection, not protection against signing a malicious or
misleading reclamation transaction. The vault does not authorize spending account assets.

The [reference-vault specification](../protocol/reference-vault/specification.md) fixes
parameter order (publisher bytes, purpose bytes, full AccountId), AccountId constructor 0
with policy/name fields, and Reclaim constructor 0 containing version 1 and that AccountId.
No datum is required. Golden vectors and explicit shape validation accompany the typed API;
do not assume the compiler validates record constructor tags or arity. Keep the script small and avoid imposing continuing-output
rules that would contradict intentional capital recovery.

## Backend flow

1. During setup derive the account identity first, then derive the vault from the publisher
   key, purpose and that full identity. Compute minimum ADA from each complete final output,
   including datum if required, script bytes and actual script address. Quotes distinguish
   publisher wallet address from the vault holding address.
2. Persist exact hosted script bytes and exact vault artifacts with version, network,
   publisher key, account identity and holding address. Write public records atomically.
   Discovery hints confer no authority: rederive/verify the vault identity and verify each
   live output's exact address, reference hash and expected shape.
3. Continue discovering historical permanently locked and ADR-011 key-hosted references.
   Existing outputs are not moved or made reclaimable by this update. Preserve their records.
4. Reclamation is a separate API operation, independent of live account-state restoration or
   unrelated account artifacts, bound to one exact transaction-output reference,
   its hosted script hash, vault address and configured publisher. The connected publisher
   must control the key committed by the vault. Re-resolve the input before constructing and
   submitting; fail on stale or substituted output. No ordinary account mutation is bundled.
5. Return the selected output's value to the publisher address. Use separate plain funds for
   fees/collateral; show the destination, full returned value and actual final fee. Include the
   small vault validator as a transaction witness so reclaiming the last reference does not
   depend on another vault reference. Correctly price reference bytes on consumed outputs,
   execution, collateral return and output minima; do not use a fixed oversized fee fixture
   as production fee logic. Recheck final body and require the publisher's transaction witness.
6. Missing operational copies use the existing repair flow: authenticate account bindings,
   retrieve retained exact bytes (or verified provider history), then publish a replacement
   into the newly connected publisher's vault. A different funded publisher is allowed.
   Rebuild pending account transactions and obtain fresh witnesses after a reference changes.

Retain funding/collateral filtering and final-body assertions. Permit a vault input only in
an explicit reclamation plan, not as incidental funding. Bound record parsing/discovery and
reject artifact/parameter/address mismatches. No blanket allowance for arbitrary script inputs.

## UI and ownership

Security shows each reference's availability and hosting type: historical locked, historical
key-hosted, or publisher vault. For vaults show the holding address, publisher identity,
selected UTxO and capital. The setup review explains that hosted capital is a separate script-address reserve, unavailable to ordinary wallet coin
selection, and requires explicit Kavach reclamation. Wallet balance displays may differ.

Expose Reclaim reference for supported vault outputs, including optional genesis-only copies.
Before preparation require acknowledgement that removal of an active copy may stop account
operations, then present the exact output, destination, amount and fee for wallet approval.
Changing the connected publisher after selecting an output requires a fresh, revalidated plan.
Do not silently reclaim, migrate or republish anything. Legacy locked outputs have no reclaim
action. Legacy key-hosted outputs retain their prior ownership but are not swept through the
new vault action. Repair and reclaim must remain distinct actions.

Persisted public artifact/profile backups survive backend restarts. Account locator alone
cannot recreate script bytes if local artifacts and provider history are both lost. A fresh
backend requires those public backups for reliable discovery. The current screen lists one usable copy per current account script hash, not an inventory
of duplicate, retired-module or orphaned pre-genesis publications; those remain reclaimable through the explicit API/SDK with retained
locator/vault data and the publisher. Portable UI export/import and background alerts remain separate work; do not imply they exist. Losing the publisher key
can strand vault capital independently of account recovery. Publisher takeover of a reference
confers no account authority, but can interrupt availability.

## Economics and compatibility

Recalculate setup capital: the prior 223.895880 ADA five-reference sample is historical, not
a guaranteed vault quote. The wrapper is supplied only when reclaiming; no promised ordinary
transfer-fee reduction follows. Measure actual publication/reclamation/transfer fees on DevKit.
Do not alter compiler pins, core artifact hashes, legacy scripts, pending setup commitments,
reward balances or recovery workers. The vault has a separately versioned artifact and wire
schema outside the sealed V1 baseline. A future vault/compiler update must retain the old
version's exact artifact and derivation support before adoption; saved old records must not
be reinterpreted with a new template or silently replaced. Existing repair may create a new vault-hosted copy;
this does not change the account address or recover old publisher capital.

## Implementation sequence and acceptance

1. Review this plan with contract, backend economics and wallet reviewers; record dispositions.
2. Add the isolated validator, typed schema/codec/deployment helper, canonical fixtures and
   compiled positive/adversarial/execution-budget tests. Verify artifact identity changes when
   publisher/account changes and existing account templates remain unchanged.
3. Implement verified vault records, publication/minimum calculations, backward-compatible
   discovery, exact-input reclamation and independent-publisher repair. Test malformed records,
   wrong owner, wrong output, stale/replayed requests, fee and final-body accounting.
4. Implement explicit UI review/acknowledgement, publisher/holding-address display and tests.
5. Run local checks/build and disposable external DevKit flows: creation and use without
   publisher account authority, wrong-owner reclamation rejection, successful owner reclaim,
   restart, missing-reference detection, independent repair, same-address transfer, and a mixed
   setup regression. Confirm the node enforces the publisher signature and ledger fee rules.
6. Record paid fees, exact output capital, script bytes/budgets, failures and final passing
   results under separate ADR-012 evidence paths. Update specification, guide and this ADR.

Independent audit, real-wallet captures and existing Phase 2 recovery/positive-reward release
gates remain open. Do not reset DevKit or weaken account security to make this hosting work.

## Initial review disposition

- Contract review: commit full AccountId in the applied parameters and typed Reclaim payload;
  enforce constructor/arity/version and spending purpose explicitly. No datum dependency or
  continuing-output rule is needed. Off-chain tooling verifies expected reference output shape;
  the owner-authorized validator may also recover mistaken deposits at its own vault address.
- Wallet review: distinguish all three hosting types; bind reclaim to an exact UTxO, show full
  return separately from fees, acknowledge removal for optional as well as active copies, and
  revalidate after a connected-wallet change. Do not promise how third-party wallets display
  the script-address reserve.
- Backend economics review: preserve legacy records; separately version and authenticate vault
  records, reclaim one exact output with plain fee/collateral funding and return its full value.
  Pinned CCL distinguishes script spending from key spending when charging consumed reference
  bytes; the vault path must be measured at the node rather than inherit the old key-reclaim
  fixture's fixed fee. Final implementation review and exact ledger qualification passed; the evidence and remaining
  release gates are recorded in the qualification report.

## Implementation outcome

Implemented the separate `ReferenceVault` validator and deployment helper, verified public
vault records, script-address publication, exact-output publisher reclamation, backward-compatible
reference discovery and independent-publisher repair. The UI distinguishes custody from account
authority and requires explicit removal acknowledgement. A locator identity accessor permits
API reclamation preparation before genesis without requiring live account-state restoration.

Local validation passes: 568 root tests, nine backend tests, one toolchain gate, 24 frontend
tests and the pinned build. Seven distinct DevKit workflows pass across eight final executions,
including mixed creation, owner rejection/reclamation, restart/repair and both short browser
lifecycles. All 26 existing contract artifacts remain unchanged. The measured vault fixture
is 697 CBOR bytes; final reclaim paid 0.376944 ADA with exact fee decomposition, and the
five-reference capital sample is 223.292480 ADA. No ordinary transfer-fee reduction is claimed.

Public evidence and unresolved production/UI boundaries are in the qualification report.
The publisher intentionally retains removal authority; account recovery does not recover
that key or its capital. Existing permanently locked outputs retain their original rules.
