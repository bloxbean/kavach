# ADR-010: Intent execution and fee sponsorship

Status: Proposed direction, not implemented network support or production qualification.

Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md), especially
sections 23.4 and 24; [ADR-009](adr-009-per-key-account-creation.md).

## Motivation

Kavach can provide an account layer for applications whose users understand actions and
their chosen authenticator but need not operate a separate fee wallet. Existing all-COSE
approval separates account authority from transaction funding. A network participant could
supply funding, collateral, transaction assembly and submission without becoming an account
authority. This extends the replaceable sponsor direction; it does not implement a solver
network, universal intent language or cross-chain custody.

## Proposed boundary

Account authority and immutable constraints stay on-chain. A replaceable off-chain execution
layer discovers requests, offers funding, constructs transactions and reports confirmation.
Application-funded providers, user-selected sponsors and eventually decentralized execution
networks can implement this layer. No privileged network/operator key becomes a core account
parameter. Relay transport and funding/settlement are separate responsibilities.

The intended user flow is action, human-readable review, required authority approval, and
confirmation. A disclosed application subsidy can remove the user's fee-signing step; the
sponsor still signs its own transaction inputs/collateral. An existing transaction-witness
policy still needs its authority witnesses. Recovery and other unsupported phone operations
cannot gain support through sponsorship alone.

## Exact intents before flexible execution

Existing intents commit exact account inputs, recipient indices/addresses/values, current
state, operation and validity. A provider may complete only a transaction satisfying those
constraints; it cannot reinterpret an opaque signed hash as a broad outcome order.
Funding selection, quotes and preparation must stabilize the fields that require approval.
Reordering or replacing signed fields requires new authorization. A replacement sponsor can
reuse evidence only if the complete signed semantics/profile/window are unchanged and the
ledger state/inputs remain valid. The current dashboard prepares a fresh request when the
sponsor changes; cross-provider handoff is not a qualified feature.

## Economics and consent

A subsidy is not a zero-cost transaction. Before execution, define who pays network fees,
reference charges, deposits/minimum-ADA top-ups and collateral losses, and whether a service
charge applies. Off-chain subscription/application funding is one possible model, not a
payment system implemented by Kavach.

Ordinary V1's bounded account fee contribution is for network fees, not an implicit solver
tip. An account-paid service charge must be an explicit signed allocation within the
supported profile and counted by the relevant approval/budget rules. Token reimbursement,
swaps, auctions and cross-chain settlement need their own qualified semantics. The current
single-recipient phone profile cannot silently add a second service-charge recipient.

## Security and availability requirements

- No spending keys or unsolicited account authority are given to providers.
- Signers verify the exact account and action, recipients, expiry and all user charges.
- Providers verify the complete transaction, own input authorization, collateral exposure
  and return address; account assets/state NFT are not collateral.
- Wrong-target, fee-padding, stale state/counter, duplicate delivery, malicious provider,
  conflicting execution, failed transaction and rollback behavior need explicit tests.
- Privacy depends on transport and discovery: a public feed can expose payment intent before
  confirmation. Encryption does not hide all metadata or make quotes trustworthy.
- Relays cannot be assumed to deliver requests, sponsors cannot be assumed willing to fund,
  and a single centralized gateway is not decentralization.
- Direct/user-selected funding must remain possible, especially for freeze, cancellation
  and recovery. No available funded provider means a liveness failure, not an auth bypass.

## Incremental delivery and qualification

1. Specify request/quote/provider APIs, capability discovery, funding consent and expiry.
2. Qualify one external sponsor on DevKit with all-COSE accounts and explicit failure paths.
3. Add multiple independent providers and authenticated failover without changing account rules.
4. Evaluate decentralized feeds/relay and economic mechanisms against privacy, censorship,
   availability, spam and settlement requirements; choose a protocol only after evidence.
5. Publish operator diversity and failover evidence before calling the deployment decentralized.

Existing live independent-sponsor tests prove only the separation primitive. No existing
intent-network standard is claimed compatible. See [the living paper](../docs/whitepaper/README.md)
for the product model. No contract, schema, script hash or deployed account changes here.
