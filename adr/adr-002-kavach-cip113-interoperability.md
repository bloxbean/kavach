# ADR-002: Kavach CIP-113 Interoperability

* **Status:** Proposed
* **Date:** 2026-09-06
* **Project:** Kavach
* **Priority:** Safety and correctness first
* **Depends on:** [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md)
* **Implementation reference:** [Cardano Foundation CIP-113 platform](https://github.com/cardano-foundation/cip113-programmable-tokens-platform)
* **Java SDK reference:** [cardano-client-lib PR #657](https://github.com/bloxbean/cardano-client-lib/pull/657)
* **Scope:** Plan compatibility before core deployment; implement full support later

## 1. Context and Evidence

Kavach should eventually manage CIP-113 programmable tokens under the same account identity, credential rotation and recovery policies as its ordinary assets. ADR-001 currently specifies ordinary assets at an account-specific payment validator. That accounting model does not automatically cover programmable-token custody.

The user-designated [platform repository](https://github.com/cardano-foundation/cip113-programmable-tokens-platform) is the primary integration reference. Its README separates the Java transaction-building backend, reference frontend and example substandards from the [core on-chain repository](https://github.com/cardano-foundation/cip113-programmable-tokens). Both are needed to evaluate interoperability. The platform describes an evolving research implementation; neither its documentation nor this ADR establishes production safety.

The core implementation uses a shared programmable-token payment credential and the address's stake credential to identify the holder, including script-controlled holders. See its [architecture](https://github.com/cardano-foundation/cip113-programmable-tokens/blob/main/documentation/02-ARCHITECTURE.md). Script-owner withdrawal execution is the proposed attachment point for Kavach, to be demonstrated against the exact contract snapshot used by the Java SDK. The older architecture documentation is background, not the authority for current redeemer layouts.

Sources were reviewed on 2026-09-06 using moving branch references. A complete compatible platform/core/CCL/substandard deployment has **not** been qualified for Kavach. Phase 0 must identify the actual contract artifacts used by the platform and record their provenance; do not assume independently selected branch heads are compatible.

The requirements below are Kavach design proposals, not claims that existing upstream validators enforce Kavach semantics.

### 1.1 CCL implementation snapshot

On 2026-09-06, GitHub API inspection found [PR #657](https://github.com/bloxbean/cardano-client-lib/pull/657) **open**, targeting `feat/basic-cip-113-integration`, at head `cde967b6a64700942417b6afa8e7e787cc85cbcb`. Its current changes include the `programmable-token` module and QuickTx/TxPlan integration; older cached descriptions calling it documentation-only are stale. This is an integration candidate, not a released Kavach dependency.

The pinned [CCL compatibility manifest](https://github.com/bloxbean/cardano-client-lib/blob/cde967b6a64700942417b6afa8e7e787cc85cbcb/programmable-token/src/it/resources/blueprint/cip113/compatibility.yml) records:

| Item | Inspected value |
| --- | --- |
| CIP-113 core commit | `9db7e0629a1509cc9d41d069f0ef0ed251601173` |
| Contract provenance label | `0.5.0-alpha.2` |
| Blueprint SHA-256 | `bd297f36e9e955d814fb3a67fbc7e51c2344b54d952d38b03ef9f32c1d43b9ad` |
| Aiken compiler provenance | `v1.1.23+8949565` |

The manifest's unapplied hashes are not deployed hashes. `contract_version` is informational; qualification must verify applied scripts, parameters and live deployment identity. No Kavach transaction tests have been run against this snapshot.

At this revision, the [base validator](https://github.com/cardano-foundation/cip113-programmable-tokens/blob/9db7e0629a1509cc9d41d069f0ef0ed251601173/validators/programmable_logic_base.ak) dispatches directly to separate transfer, third-party and unfracking delegates read from authenticated live protocol parameters. The [transfer delegate](https://github.com/cardano-foundation/cip113-programmable-tokens/blob/9db7e0629a1509cc9d41d069f0ef0ed251601173/validators/transfer.ak) replaces the earlier combined global dispatcher. The prototype must use this exact schema rather than mixing generations of the implementation.

## 2. Decision: Plan Now, Deliver Later

Specify and prove a narrow CIP-113 owner-adapter boundary before freezing the initial Kavach core scripts and wire schema. Full CIP-113 wallet functionality remains outside the initial ordinary-transfer release.

The intended extension MUST:

- Preserve the existing `AccountId`, state NFT, state-validator hash and ordinary asset-validator hash when added to an already-created compatible account.
- Use the same authenticated current state and operation authorities; never bind ownership directly to a replaceable device key.
- Enforce Kavach safety invariants in immutable adapter code, independently of replaceable authorization-module approval.
- Keep CIP-113 token custody and token-specific rules intact.
- Introduce no generic permission to invoke arbitrary scripts or sign arbitrary digests.
- Remain language-independent; JuLC is the first Kavach implementation, and Aiken upstream contracts are external integration dependencies.

Hash preservation is an **acceptance criterion**, not an unconditional promise. If the prototype requires changing a frozen state schema, core parameter, checkpoint or incompatible authorization ABI, resolve that design before production deployment. A future core change requires an explicit migration ADR.

## 3. Custody and Script Layout

| Holding | Payment credential | Stake credential |
| --- | --- | --- |
| Ordinary Kavach assets | Existing account-specific asset validator | None in ordinary V1 |
| CIP-113 holdings | Supported CIP-113 programmable-token base script | Account-specific Kavach owner adapter |

```text
Ordinary asset inputs                 CIP-113 inputs
        |                                  |
Kavach asset validator                CIP-113 base/transfer validators
        |                                  | require owner execution
Ordinary core checkpoint              Kavach CIP-113 owner adapter
        |                                  |
        +------ authenticate current ------+
                 WalletState NFT
                       |
              installed auth module
```

The diagram shows two separate transaction paths, not an initial mixed transaction. The CIP-113 path also executes the required token-specific validators. A reference to a script is not evidence that it executed.

The adapter's immutable parameters bind at least the full `AccountId`, expected state-validator hash, Kavach deployment/core domain, and the supported CIP-113 deployment identity. The latter must authenticate the base/transfer scripts and protocol-parameter/registry identities used by that deployment. Resolve the final representation and parameter graph in Phase 0.

Do not put the adapter hash into an immutable core parameter that must already exist to derive `AccountId`; this risks circular derivation and prevents later deployment. The adapter is derived after account creation from existing immutable identifiers. Its own rewarding-purpose credential supplies its identity for intent binding.

The adapter does not become the ordinary asset validator, state validator or current auth-module hash. Its stake-credential position is an ownership hook, not an expansion of Kavach's staking/reward/governance features.

## 4. Compatibility Boundary to Freeze Before Core Deployment

### 4.1 State and authority

The adapter MUST authenticate exactly one canonical reference state using NFT policy/name/quantity, expected state address, schema, account identity and immutable domain/core binding. It rejects `Frozen` and `RecoveryPending`, stale versions, malformed configuration and unsupported schemas. It does not consume or mutate state during a transfer.

It reads the currently installed authorization module from that state. Recovery and credential rotation therefore affect subsequent owner-authorized token transfers without replacing the adapter. Referenced state becomes stale when consumed; ordinary confirmation, ordering and rollback limits from ADR-001 still apply.

### 4.2 Dedicated authorization operation

Define a versioned `Cip113Transfer` intent and module request before freezing the protocol schema. The typed signing envelope must bind:

- Protocol/schema version, chain/deployment domain, `AccountId`, immutable core binding and current state version.
- Operation tag, owner-adapter script hash and CIP-113 deployment identity.
- Exact nonempty owned input references, exact recipient output allocations and validity interval.
- Full asset quantities and supported output forms; initial account fee contribution is zero.

The adapter derives the digest from this typed envelope. It requires the configured module's exact zero-valued withdrawal and rewarding redeemer for the same envelope. The module independently authenticates current state, checks operation support, verifies the complete signed envelope and applies the configured policy. Neither side accepts an independently supplied trusted digest.

An ordinary `SpendIntent` cannot authorize this operation, and a `Cip113Transfer` signature cannot authorize ordinary spending, another adapter, another deployment or administration. Unknown operations fail closed. Phase 0 must choose a concrete typed ABI that supports this separation without changing existing state encoding.

The first ordinary-only auth module may reject `Cip113Transfer`. Later activation may install a compatible module/configuration using the existing old-module AdminPolicy flow. That changes the module hash/configuration, not core hashes. Compatibility and target validation must be tested; an opaque config slot alone is not evidence that an upgrade works. Do not require a new top-level state field for each integration.

### 4.3 Core checkpoint responsibility

The proposed baseline is a separate immutable owner adapter that performs the complete lifecycle, intent, replay and value checks for its custody domain. It does not depend on the ordinary checkpoint accepting an unknown operation. Shared on-chain libraries may reduce implementation drift, but the deployed adapter remains separately reviewed code with its own hash.

Reusing a transaction checkpoint is optional only if the prototype proves its exact domain and accounting behavior. Simply requiring the current auth module to execute is insufficient. Adding an integration must never weaken the ordinary core's input checks or permit general external-script authorization.

## 5. Initial Supported Transaction Shape

The first integration supports one source Kavach owner adapter, one CIP-113 deployment and one intent per transaction, with plain owner-authorized transfers. Receiving outputs may belong to another holder, including another Kavach account; the restriction concerns consumed source holdings, not recipients.

It rejects ordinary Kavach asset inputs, state transitions, other owner adapters' inputs at the selected base script, mint/burn, administrative token actions, unsupported certificate/governance actions and unsupported output forms. Require external key-controlled sponsor inputs for fees and collateral. All required withdrawals have amount zero. Registration/setup is a separately specified transaction, not an exception hidden inside the spend path.

The adapter scans the complete transaction. Owned inputs are identified by both the authenticated CIP-113 payment credential and this adapter's inline script stake credential. The exact signed list must equal that complete nonempty set. Invalid or pointer stake forms and attempts to invoke this adapter for another custody deployment are rejected under the specified transaction shape.

The proposed initial output format uses exact full addresses, no datum and no reference script. Recipients and change occupy disjoint, unique indices. Change requires the same authenticated base script and owner adapter. Sending tokens to Kavach's ordinary enterprise address is not valid programmable-token change.

For **every** asset, including lovelace and incidental non-programmable assets in consumed holdings:

```text
owned inputs = exact signed recipient allocations + valid owned change
account-funded fee contribution = 0
```

The sponsor pays fees. This deliberately avoids sharing an account fee cap across ordinary and CIP-113 custody in the first integration. Deposited minimum ADA is accounted for, never silently treated as relayer income. Unknown extra assets must be preserved; matching only the named programmable token is insufficient.

The adapter must require `SpendViaTransfer` on every owned base-script input, the supported transfer delegate/redeemer and authenticated deployment, while the CIP-113 validators enforce registry and token-specific conditions. Fake protocol parameters, registry evidence or lookalike token metadata cannot establish support. Final withdrawal allowlisting, redeemer bindings, deployment proof validation and transaction bounds are Phase 0 specifications.

Specialized datum-bearing tokens, UTxO restructuring, mixed custody, multiple source accounts, swaps, issuance and burns require separately specified operations and tests. Unsupported deposits must not be advertised as spendable. The SDK must distinguish discovered holdings from supported, transferable holdings.

## 6. Issuer Authority and Recovery Limits

CIP-113 substandards may include third-party transfer or seizure paths. Those paths have their own authority and may bypass normal holder authorization; the adapter cannot enforce a veto when it is not invoked. Consult the [core authority model](https://github.com/cardano-foundation/cip113-programmable-tokens/blob/main/documentation/03-CONTROL-SCOPE-AND-ADMIN-AUTHORITY.md) and the platform's [freeze-and-seize implementation](https://github.com/cardano-foundation/cip113-programmable-tokens-platform/tree/main/src/substandards/freeze-and-seize) for the pinned deployment's actual behavior.

The inspected base validator permits in-place replacement of its delegate credentials through live protocol parameters. Consequently, a stable CIP-113 address does not imply immutable custody rules. Qualify upstream governance and coordination-state updates explicitly. The Kavach adapter must reject unapproved transfer delegates when invoked, but it cannot veto an upstream replacement that bypasses owner invocation entirely. Treat those upgrade powers as part of the external custody trust model, including loss of transferability when a pinned delegate becomes obsolete. Do not promise safety solely from an unchanged base hash.

Kavach freeze blocks the owner-authorized path after its state transition is accepted. It does not guarantee prevention of issuer actions. Kavach recovery restores account authorization, not exemption from issuer restrictions or restoration of seized assets. Denylisting a stable owner credential may continue to restrict it after credential recovery.

Before enabling a token, document and test its third-party powers, especially treatment of script-owned UTxOs, mixed assets, lovelace, companion tokens and datum changes. Do not infer immunity from seizure merely because the holder is a script. If a token's control model violates the supported custody guarantees, mark it unsupported rather than bypassing those controls.

## 7. Script Hashes, Activation and Upgrade Consequences

| Change | Consequence |
| --- | --- |
| Deploy an adapter for a compatible existing account | New adapter hash and CIP-113 address; existing core hashes and `AccountId` stay unchanged |
| Rotate credentials or complete recovery | State changes; adapter hash/address remain unchanged |
| Install a compatible authorization module | New module hash and state version; adapter and ordinary addresses remain unchanged |
| Change adapter code, compiler output or applied parameters | New adapter hash and CIP-113 holding address |
| Change ordinary core code or applied parameters | Potential new core hashes/addresses; separate migration design required |
| Adopt another CIP-113 deployment | New deployment binding and normally a distinct adapter/address; interoperability is not assumed |

SDK enablement must verify a reviewed adapter artifact, supported upstream deployment and usable account authorization before presenting a receive address. Arbitrary depositors can still construct outputs, so UI activation is not an on-chain deposit gate. Do not introduce a universal on-chain adapter registry without a separate justification.

Adapter replacement does not update old holdings in place. It requires an explicitly authorized transfer under the old adapter and applicable token rules. The first profile does not silently authorize adapter migration; specify that operation before relying on it. Issuer restrictions or a broken old adapter can prevent migration. No arbitrary upgrade hook is proposed to conceal this risk.

## 8. Delivery Plan and Acceptance Gates

### Phase 0 — mandatory before freezing Kavach core

1. Pin a compatible platform/core/CCL/substandard revision set, beginning with the PR #657 snapshot above and inspect actual deployment artifacts and schemas. Record commits, compiler versions, script hashes, parameters, network and registry identities; do not treat the platform frontend's configuration as trusted on-chain evidence.
2. Freeze the typed adapter authorization boundary and prove it works with the planned state schema and administration flow. Specify count/size/budget bounds and withdrawal/certificate handling.
3. Create an account with the candidate ordinary-only deployment. Freeze its core artifacts and state encoding. Subsequently deploy the adapter and, if needed, upgrade only the authorization module through the existing flow.
4. On a controlled ledger, receive and transfer a test programmable token, rotate credentials, freeze/unfreeze, and complete recovery. Prove expected rejection/acceptance and unchanged state NFT, ordinary/state core hashes and adapter address through credential changes.
5. Exercise the platform's basic and freeze-and-seize substandards, including script-owner execution and all relevant non-holder paths. Confirm registration/deposits, zero withdrawals and deregistration protection for the exact implementation.
6. Publish the result and revise this ADR if compatibility needs core changes. A schema sketch or fabricated script context alone does not satisfy this gate.

This work is a bounded compatibility prototype, not full CIP-113 release support. Production core freeze is contingent on its outcome if later hash-preserving integration is promised.

### Later implementation — after the boundary passes

Build the JuLC adapter and Java SDK support under `com.bloxbean.cardano.kavach`, using Gradle. Suggested optional modules are `kavach-cip113-contracts` and `kavach-cip113-sdk`; no modules are created by this ADR.

Prefer the qualified cardano-client-lib `programmable-token` API and CIP-113 adapter for Java transaction construction, aligned with PR #657. Use the platform backend and substandards as interoperability references and test counterparts, not as an implicit trusted signer or required hosted service. Keep Kavach-specific state, authorization and signing logic in Kavach; do not fork the generic CIP-113 builder without evidence that an upstream extension cannot meet the requirement. Discovery must group holdings by supported deployment and owner credential, verify state/configuration, and report ordinary and programmable balances without double counting. Handle stale state, registry changes, unavailable reference scripts, transaction rejection and rollback. Show issuer restrictions separately from Kavach freeze.

Require language-neutral fixtures and compiled-script conformance across the JuLC adapter, Java builder and pinned upstream Aiken validators. A future Aiken Kavach adapter must pass the same acceptance/rejection suite; language independence does not imply identical hashes.

### CCL composition and signing requirements

The inspected [CCL module README](https://github.com/bloxbean/cardano-client-lib/blob/cde967b6a64700942417b6afa8e7e787cc85cbcb/programmable-token/README.md) describes `ProgrammableTokenService`, a QuickTx extension, typed programmable-token intents and TxPlan codecs. Ordinary `payToAddress` does not route programmable tokens. Its [build extension](https://github.com/bloxbean/cardano-client-lib/blob/cde967b6a64700942417b6afa8e7e787cc85cbcb/programmable-token/src/main/java/com/bloxbean/cardano/client/programmabletoken/cip113/tx/Cip113BuildExtension.java) prepares inputs, finalizes indices before evaluation, re-finalizes after balancing and verifies stability. Kavach must integrate with that lifecycle, not append unchecked redeemers to its output.

The Kavach prototype must prove the following; these are requirements, not assertions of existing CCL support:

- Script-owner address construction and discovery, exact account-input selection, explicit owner-adapter withdrawal/evidence, canonical state references and external sponsor/collateral separation are all expressible. Review CCL's `from(owner)` behavior; key-owner examples do not prove smart-account ownership support.
- Resolve programmable recipients, ledger ordering, output values and owned input sets before requesting a Kavach intent signature. CCL semantic intents and YAML plans are build instructions, not signed Kavach authorizations.
- After adding evidence and rebalancing, rederive the Kavach envelope from the final transaction and compare every signed field. Any changed signed input, recipient index/value/address, validity bound or deployment requires rejection or fresh authorization. Never silently update the signed message. Final transaction witnesses come after the transaction body stabilizes.
- Distinguish token transfer-logic redeemers from Kavach owner and auth-module redeemers. Detect credential collisions and conflicting redeemers across extensions; deduplication must not erase checks. The SDK must be able to register both extensions without imposing a QuickTx dependency on Kavach.
- Preserve Kavach's stricter supported transaction shape even where CCL supports more operations. Respect the pinned CCL profile's single-policy programmable outputs and whole-transaction composition constraints; map multiple token policies to distinct signed allocations.
- TxPlan/YAML round trips must preserve intent meaning and deployment binding. Runtime variables are resolved before signing. A reconstructed or rebuilt plan needs final envelope verification, not assumed reuse of earlier authorization.

Record API gaps as concrete upstream work items during the prototype; do not report PR #657's own tests as evidence of Kavach adapter compatibility. Pin a release or exact reviewed commit for reproducibility, and requalify when its API or contract artifacts change.

### Release gate

Before enabling real holdings, complete independent review of the adapter, authorization changes and cross-protocol transaction composition. Reassess the selected upstream revision's security status and resolve applicable findings. Publish reproducible artifacts and supported token/deployment profiles. Neither a successful prototype nor upstream tests constitute a Kavach integration audit.

## 9. Required Adversarial Coverage

| Case | Required outcome |
| --- | --- |
| Fake state NFT/address, stale version, wrong account/domain/deployment | Reject |
| Ordinary spend signature reused for CIP-113, or reverse | Reject |
| Evidence approves another adapter or altered recipient/amount | Reject |
| Missing module/transfer/owner execution, wrong purpose or redeemer | Reject |
| Additional owned inputs, omitted incidental assets, reused recipient/change output | Reject |
| Wrong owner change, tokens escape custody, sponsor extracts owned lovelace | Reject |
| Multiple source owners, mixed ordinary spending, state mutation, unsupported mint/burn | Reject under the initial profile |
| Frozen or recovery-pending state; old credential after rotation/recovery | Reject owner-authorized transfer |
| New authorized credential after valid recovery | Accept, subject to token rules |
| Competing freeze/spend or registry update; rollback | Correct ledger ordering and stale-reference handling; no priority claim |
| Issuer freeze/seizure or special restructuring path | Match pinned upstream authority; expose limits without claiming adapter veto |
| Unsupported datum/asset profile, duplicate evidence, oversized input/output lists | Reject safely within documented limits |

## 10. Alternatives and Outstanding Decisions

Putting CIP-113 tokens directly at the ordinary Kavach payment script conflicts with the selected custody model. Using a device key as the owner credential loses stable account recovery. Using the currently installed auth-module hash as owner makes module upgrades change the holding address. A stable account-specific adapter avoids those couplings but adds separately audited code and external protocol dependencies.

Still to resolve: complete platform/substandard/deployment qualification against the inspected CCL/core snapshot; concrete typed authorization ABI; adapter parameter encoding; registration lifecycle; supported token profiles and special paths; output/datum restrictions; protocol limits; SDK discovery and activation metadata. These are explicit prototype deliverables, not permission to deploy with unspecified behavior.

The accepted planning direction is to resolve these boundaries now, retain a narrow ordinary V1, and ship full CIP-113 support only after its own validation gates pass.
