# ADR-001: Kavach — Programmable Smart Account Architecture for Cardano

* **Status:** Proposed
* **Date:** 2026-09-06
* **Project:** Kavach
* **Repository:** `bloxbean/kavach`
* **Protocol:** Language-independent Cardano smart-account protocol
* **Initial implementation:** JuLC / Plutus V3; Gradle; Java package `com.bloxbean.cardano.kavach`
* **Priority:** Safety and correctness before cost, throughput, or convenience
* **Reference Wallet:** Yano Wallet
* **Decision Owners:** BloxBean
* **Scope:** Recoverable and programmable smart accounts for Cardano
* **Related decision:** [ADR-002 — CIP-113 interoperability](adr-002-kavach-cip113-interoperability.md)

## 1. Context

Traditional Cardano wallets are primarily controlled by cryptographic keys derived from a seed phrase. This model provides strong self-custody but introduces several usability and recovery problems:

* loss of the seed phrase can result in permanent loss of funds;
* compromise of the primary signing key can result in immediate loss of funds;
* changing authentication mechanisms generally requires moving funds to another wallet;
* device-native authentication such as Face ID does not map cleanly to Cardano's normal payment-key model;
* social recovery, delayed recovery, emergency freeze, session authorization, and spending policies are difficult to express with ordinary wallets;
* dApps typically request signatures over complete transactions rather than user-level semantic actions;
* authentication technology evolves faster than deployed smart-contract protocols.

JuLC allows Cardano validators to be implemented in Java and provides an opportunity to build a wallet around a different abstraction:

> The wallet is the smart account. Keys, devices, guardians, remote signers, and other authentication mechanisms are replaceable authorization mechanisms for that account.

This project is named **Kavach**.

Kavach means *armor* or *protective shield*, reflecting the project's primary objective: protecting ownership of a Cardano account through programmable authorization, recovery, credential rotation, and security policies.

Kavach will be developed as an independent protocol/framework.

**Yano Wallet will initially serve as the reference wallet and primary UX for Kavach**, but Kavach MUST NOT depend on Yano Wallet and SHOULD be integrable by other wallets and applications.

---

## 2. Decision

This ADR remains **Proposed**. Requirements below define the intended protocol; they are not claims of implemented, benchmarked, or audited behavior. Sections 22–30 refine the initial design into a proposed V1 boundary and validation plan.

**Safety and correctness take precedence over performance, extensibility, and wallet UX.** An optimization may be adopted only after its invariants are specified and tested. Kavach is a language-independent protocol: JuLC is the first implementation, while future implementations in Aiken or other Cardano languages must satisfy the same wire-format, state-transition, and adversarial conformance requirements.

We will specify **Kavach** as a programmable smart-account protocol and implement its first version with JuLC, consisting of:

1. a stable wallet/account core;
2. a canonical `WalletState`;
3. a unique state-thread NFT identifying that state;
4. replaceable authorization modules;
5. signed semantic Kavach intents;
6. transaction-level authorization;
7. independent replay-protection mechanisms;
8. separate spend, administration, freeze, and recovery authorities;
9. SDKs and signer interfaces for off-chain integration.

The account's identity MUST NOT depend on the currently configured signing key.

Compatible authorization modules MUST be replaceable without changing account identity or moving normal account assets. This does not imply arbitrary cryptographic support or in-place replacement of immutable core validators; see sections 22 and 27.

---

## 3. Project Positioning

Kavach is not intended to be another standalone wallet application.

It is a **programmable smart-account protocol/framework for Cardano**.

```text
                    Kavach
          Programmable Smart Account
                       │
        ┌──────────────┼──────────────┐
        │              │              │
     Contracts        SDK         Auth Modules
        │              │              │
        └──────────────┼──────────────┘
                       │
              Wallet integrations
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
     Yano Wallet   Other Wallets   dApps
```

Yano Wallet will be the initial reference implementation and proving ground.

The intended relationship is:

```text
JuLC
  │
  │ smart-contract language/compiler
  ▼
Kavach
  │
  │ smart-account protocol/framework
  ▼
Yano Wallet
  │
  │ reference user experience
  ▼
User
```

This separation allows Kavach to evolve independently and potentially become reusable infrastructure across the Cardano ecosystem.

---

## 4. Architectural Principles

### 4.1 Account identity is independent of credentials

A Kavach account has a permanent `WalletId` / `AccountId`.

The protocol standardizes on `AccountId`; `WalletId` is only an explanatory alias in earlier material. `WalletState` remains the state type name in this ADR.

`AccountId` MUST be the full state-thread NFT asset identifier: policy ID plus asset-name bytes. An asset name alone is not unique.

Conceptually:

```text
Kavach AccountId
       │
       ▼
State Thread NFT
       │
       ▼
Canonical WalletState
```

Keys are authorization credentials associated with this identity.

Therefore:

```text
Kavach Account
      !=
device credential
      !=
Cardano payment key
      !=
recovery credential
```

Replacing a credential does not replace the Kavach account.

---

### 4.2 WalletState MUST be authenticated by a state-thread NFT

A `walletId` or `accountId` datum field alone is insufficient because an attacker could construct another UTxO containing the same identifier and malicious credentials.

Each Kavach account MUST therefore mint a unique one-shot NFT during account creation.

Exactly one valid `WalletState` MUST contain this NFT.

Normal spends MUST locate the canonical state in reference inputs. State transitions MUST instead consume its current UTxO and validate exactly one successor; they cannot authorize against a lookalike or successor datum. NFT quantity, state address and schema checks are mandatory in both cases.

Conceptually:

```text
Kavach WalletState
────────────────────────
State NFT        ✓
stateVersion     42
authModuleHash   ...
authConfig       ...
recoveryState    ...
mode             Normal
```

The Kavach validator MUST reject lookalike WalletState UTxOs that do not contain the expected state NFT.

---

## 5. Stable Kavach Core

The Kavach core SHOULD remain deliberately small and stable.

Its responsibilities include:

* identifying the canonical `WalletState`;
* validating the state-thread NFT;
* enforcing account lifecycle rules;
* enforcing intent semantics;
* enforcing replay protection;
* enforcing value conservation;
* enforcing freeze/recovery state;
* requiring the configured authorization checkpoint;
* controlling authorization-module upgrades.

Cryptographic authentication details SHOULD NOT be permanently embedded into the core where avoidable.

Conceptually:

```text
                 Stable Kavach Core
                         │
           ┌─────────────┴─────────────┐
           │                           │
   WalletAssetValidator       WalletStateValidator
           │                           │
           └─────────────┬─────────────┘
                         │
                   WalletState
                         │
                  authModuleHash
                         │
                         ▼
                  Auth Module
```

---

## 6. Canonical WalletState

A conceptual state representation is:

```java
// Conceptual schema, not compiled Java or a frozen wire format.
record WalletState(
    BigInteger schemaVersion,
    AccountId accountId,
    DeploymentDomain deploymentDomain,
    CoreBinding coreBinding,
    BigInteger stateVersion,
    AuthModuleRef authModule,
    PlutusData authConfig,
    BigInteger recoverySequence,
    BigInteger recoveryDelayMillis,
    AccountMode mode
) {}
```

The exact implementation MUST follow the supported JuLC Java subset and ledger-data encoding.

`authConfig` is intentionally opaque to the stable Kavach core.

The currently active authorization module owns interpretation of this data, including distinct spend, administration, freeze, unfreeze, recovery-initiation and recovery-cancellation policies. Opaqueness is a trust boundary: the core can enforce operation separation, but cannot prove that arbitrary module code actually uses independent authorities. Module conformance and review are therefore security-critical.

`AccountMode` is a tagged union of `Normal`, `Frozen`, and `RecoveryPending`, avoiding contradictory booleans. Pending recovery carries a proposal commitment, attempt sequence, target configuration, and execution deadline. `CoreBinding` authenticates the asset validator and checkpoint; exact schema tags and lengths are a Phase 0 deliverable.

This allows future authorization modules to introduce new credential and policy types without requiring the Kavach core to understand them.

---

## 7. Kavach Authorization Modules

Authorization is implemented by a separate module.

The roadmap includes the following candidates; only the section 22 subset is proposed for V1:

```text
Ed25519 authorization
Cardano signer authorization
secp256k1 authorization
remote signer authorization
BLS threshold authorization
P-256 authorization
passkey authorization
future authentication mechanisms
```

An authorization module answers:

> Is the supplied authorization evidence sufficient to authorize this Kavach intent under the account's current policy?

The module may interpret `authConfig` as required.

---

### 7.1 Credentials

A credential identifies an authorization authority.

Examples:

```text
phone
hardware wallet
remote signer
guardian
enterprise HSM
recovery service
```

Credentials SHOULD be referenced using stable `CredentialId`s.

Authorization evidence MUST NOT be allowed to inject arbitrary public keys.

Instead:

```text
AuthEvidence
    credentialId
    signature
```

The active Kavach authorization module resolves `credentialId` against authenticated configuration from the canonical `WalletState`.

---

### 7.2 Authentication and authorization are separate

An external authentication mechanism does not necessarily correspond directly to an on-chain signature algorithm.

For example:

```text
Google OAuth
     │
     ▼
Kavach Remote Signer
     │
     ▼
Ed25519 HSM/KMS key
     │
     ▼
Kavach Intent signature
```

The Cardano validator does not verify OAuth tokens.

It verifies the trusted remote signer's cryptographic signature.

This enables off-chain authentication mechanisms such as:

* Google OAuth/OIDC;
* Sign in with Apple;
* Microsoft Entra;
* enterprise SSO;
* passkeys;
* magic links;
* custom identity providers.

The Kavach protocol remains independent of those systems.

---

## 8. Authorization Policies

Credentials and policies are separate concepts.

A credential answers:

> Who can authenticate?

A policy answers:

> Which credentials, and how many, are required for this operation?

Roadmap policy primitives include (cumulative limits and arbitrary trees are not V1 features):

```text
Credential
AnyOf
AllOf
Threshold
Timelock
SpendingLimit
Role
```

Example:

```text
Spend =
    AnyOf(
        Phone,
        HardwareWallet
    )

HighValueSpend =
    AllOf(
        Phone,
        HardwareWallet
    )

Recovery =
    Threshold(
        2,
        BackupDevice,
        Guardian,
        RecoveryService
    )
    AND Delay(48 hours)
```

---

## 9. Privilege Separation

Kavach MUST NOT use one unrestricted authorization policy for every operation.

At minimum, authorization MUST distinguish:

```text
SpendPolicy
AdminPolicy
RecoveryPolicy
FreezePolicy
```

A compromised everyday spending credential MUST NOT automatically permit an attacker to:

* replace the authorization module;
* remove recovery;
* replace guardians;
* lower recovery thresholds;
* disable security controls.

For example:

```text
Spend
    phone

Change primary credential
    phone + guardian

Change authorization module
    AdminPolicy

Recovery
    2-of-3 guardians + delay

Disable recovery
    unsupported in V1; future policy requires a separate ADR
```

A core Kavach invariant is therefore:

```text
Spend authority
      !=
administrative authority
      !=
recovery authority
```

---

## 10. Authorization Module Upgrade

An existing Kavach account MUST be able to replace its authorization module.

For example:

```text
AuthV1
  Ed25519
  RemoteSigner

       ↓ authorized upgrade

AuthV2
  Passkey
  P-256
  New policy types
```

The upgrade consumes and recreates `WalletState` with:

```text
stateVersion + 1
new authModuleHash
new authModuleVersion
new authConfig
```

The **currently installed authorization module** MUST authorize installation of its successor.

The proposed new module MUST NOT be able to authorize its own installation. Its separate configuration-validation check may reject an unusable successor, but cannot substitute for old-module administration approval. All upgrade evidence must bind the exact new hash, ABI version and configuration commitment.

This prevents:

```text
Malicious AuthV2
       ↓
AuthV2 approves itself
       ↓
account takeover
```

Such a transaction MUST be rejected.

This property allows authentication technology to evolve independently of the Kavach account.

---

## 11. Kavach Intents

Users authorize semantic account actions rather than blindly authorizing arbitrary transaction structures.

These signed actions are called **Kavach Intents**.

Examples include:

```text
SpendIntent
RecoveryIntent
FreezeIntent
CredentialChangeIntent
PolicyChangeIntent
AuthModuleUpgradeIntent
SessionIntent
```

Conceptual V1 shape (wire tags and concrete JuLC types remain to be specified):

```java
record SpendIntent(
    IntentDomain domain,
    ExactAccountInputs replayBinding,
    RecipientAllocations recipients,
    IntentValidity validity,
    BigInteger maxAccountFeeLovelace
) {}
```

---

## 12. Intent Domain Separation

Every signed Kavach Intent MUST include sufficient domain information to prevent cross-account, cross-network, and cross-protocol replay.

Conceptually:

```text
IntentDomain {
    protocolVersion,
    chainDomain,
    accountId,
    coreBinding,
    stateVersion
}
```

The signed message SHOULD conceptually be:

```text
KAVACH_INTENT
||
protocolVersion
||
chainDomain
||
accountId
||
coreBinding
||
stateVersion
||
canonicalIntent
```

The `KAVACH_INTENT` domain separator distinguishes this signing protocol. The diagram is conceptual, not an ambiguous byte-concatenation specification. `chainDomain` MUST include an explicit deployment discriminator, not only the mainnet/testnet network tag. The expected domain is authenticated in deployment parameters/state; a relayer-supplied network label is insufficient. Test deployments MUST use distinct domains. Identical cloned ledger histories cannot be distinguished by intent fields alone.

---

## 13. Intent Serialization

The validator MUST derive the signed message from the typed intent itself.

The redeemer MUST NOT supply an independent `intentMessage` trusted by the validator.

Conceptually:

```text
typed Kavach Intent
        │
        ▼
serialiseData(intent)
        │
        ▼
domain-separated hash
        │
        ▼
authorization module
        │
        ▼
signature verification
```

The on-chain representation is the source of truth.

Kavach SDK implementations MUST generate exactly the same canonical representation.

Golden test vectors MUST verify encoding compatibility across SDK and contract-language implementations. Section 25 defines the requirements for freezing that wire format.

---

## 14. Replay Protection

Authentication and replay protection are independent concerns.

Authentication answers:

> Who authorized this?

Replay protection answers:

> Why can this authorization only be used in the intended context?

Candidate replay mechanisms include (V1 uses exact account-input binding for spends and state consumption for transitions):

```text
Exact input binding
Anchor UTxO
State sequence
One-shot capability
State version
Expiry
```

---

### 14.1 Exact Spend Intent

A high-assurance intent may bind exact account inputs:

```text
Intent
    inputs:
       txA#0
       txB#1
```

Once those UTxOs are consumed, the intent cannot be replayed.

---

### 14.2 Anchor Intent

For better relayer flexibility, an intent may require one account anchor UTxO:

```text
anchor = txABC#2
```

The relayer may select additional account inputs, but the anchor MUST be consumed.

After successful execution the anchor no longer exists, preventing replay.

---

### 14.3 State Version

Every Kavach Intent MUST bind the current `WalletState.stateVersion`.

For example:

```text
intent.stateVersion = 42
WalletState.version = 42
```

After:

```text
freeze
credential rotation
policy change
recovery request
authorization module upgrade
```

the state becomes:

```text
WalletState.version = 43
```

Outstanding intents bound to version 42 become invalid.

Administrative/security changes invalidate older intents once the state transition is accepted in the ledger. Submission or mempool presence alone does not freeze an account; a competing spend may be ordered first, and rollback can restore an earlier state.

---

### 14.4 Recovery Sequence

Recovery operations are intentionally serialized.

Recovery therefore uses:

```text
recoverySequence
```

Starting each recovery attempt increments the sequence, including attempts subsequently cancelled. Completion preserves that attempt number; see section 26.

`stateVersion` and `recoverySequence` have distinct roles:

* `stateVersion` invalidates authorization following any security-relevant WalletState mutation;
* `recoverySequence` orders recovery attempts and prevents recovery-specific authorization reuse.

---

Expiry and state-version binding are additional restrictions, not standalone single-use mechanisms: the same state can be referenced by many spends. Anchor intents, capability tokens and sequence-based spending are deferred until separately specified.

## 15. Double-Satisfaction Protection

Cardano spending validators execute independently for each script input.

A transaction MUST NOT be allowed to satisfy multiple independent Kavach intents using the same recipient output.

V1 therefore enforces:

> At most one spend authorization intent per Kavach AccountId per transaction.

All Kavach asset inputs for that account participating in the transaction are collectively authorized by that intent.

V1 also rejects transactions consuming assets or state from multiple Kavach accounts. Recipient outputs use unique explicit indices, disjoint from account-change outputs. All account input redeemers must identify the same account and intent digest. The shared core checkpoint accepts one account only; an input belonging to another account cannot satisfy its account binding. Equivalent enforcement is required if Phase 0 selects a different checkpoint layout. Future multi-account or multi-intent support MUST introduce transaction-wide allocation rules preventing any output value from being credited twice; tagging alone is not a proof of conservation.

---

## 16. Transaction-Level Authorization

Repeating expensive authorization logic for every account input is undesirable.

Kavach proposes a stable transaction checkpoint and a separate replaceable authorization module, both invoked through zero-valued script withdrawals. This is a **Phase 0 feasibility gate**, not an established cost or safety claim. The known withdrawal pattern is described in [CIP-112](https://cips.cardano.org/cip/CIP-0112); its proposed new Observe purpose is not a V1 dependency.

```text
Account asset inputs
    │ each requires the fixed core checkpoint + same intent digest
    ▼
Stable core checkpoint (zero withdrawal)
    │ authenticates state; checks lifecycle, intent, replay, value
    │ requires the configured module's exact withdrawal and redeemer binding
    ▼
Replaceable auth module (separate zero withdrawal)
      authenticates old/current state and operation; verifies policy/evidence
```

The core MUST NOT delegate value conservation, replay or lifecycle safety to replaceable code. The module decides authorization only. Merely including module bytes or a reference script does not execute it. The caller MUST require the expected script credential in withdrawals with amount zero, locate its rewarding-purpose redeemer, and bind it to the account, current state version, operation and core-derived intent digest. The module MUST validate the same tuple against authenticated state/configuration; it must not trust a caller-supplied digest disconnected from the typed intent.

State transitions perform their mandatory checks in `WalletStateValidator`, using the consumed old state, and require old-module authorization where appropriate. Recovery completion follows section 26. V1 forbids combining a state transition with normal asset spending.

Phase 0 MUST validate script registration/deposits, zero withdrawals, distinct script hashes, purpose/redeemer lookup, transaction assembly and execution budgets against the target ledger. Any registration authority and deregistration behavior must be specified so a third party cannot disable required checkpoints. Reference-script publication/removal is an availability concern, not authorization. See [CIP-33](https://cips.cardano.org/cip/CIP-0033).

If two checkpoints are impractical, retain direct core checks and a proven authorization path, even at higher cost. Do not collapse core invariants into an upgradeable module merely to save execution units.

---

## 17. Value Conservation

Flexible/anchor intents MUST explicitly constrain Kavach-controlled value.

A relayer MUST NOT be able to add additional Kavach UTxOs and redirect their value.

For a spend:

```text
sum(Kavach inputs)
=
authorized recipient value
+ outputs returning to Kavach account
+ explicitly allowed account fee contribution
```

The intent MUST contain a maximum account-funded fee when account assets may contribute to fees.

For example:

```text
maxAccountFeeLovelace = 2_000_000
```

Any excess value not explicitly authorized for recipients or bounded fees MUST return to Kavach-controlled outputs.

---

## 18. Recovery

Recovery is a first-class Kavach operation.

Possible recovery authorities include:

```text
backup device
family guardian
hardware key
remote recovery service
enterprise HSM
BLS guardian committee
```

Example:

```text
Recovery =
    Threshold(
        2,
        BackupDevice,
        Spouse,
        RecoveryService
    )
    AND
    Delay(48 hours)
```

Kavach recovery changes authorization authority rather than reconstructing the lost private key.

```text
Old device key
       X

New device
       │
generate new credential
       │
guardians authorize
       │
recovery delay
       │
       ▼
WalletState updated
```

The same Kavach AccountId and assets remain.

---

## 19. Recovery and Automatic Freeze

Starting recovery MUST automatically place the account into a restricted/frozen state.

Once initiation is accepted, the restricted state blocks ordinary spends during the recovery delay. It does not prevent a competing spend from being accepted before initiation.

```text
NORMAL
   │
   │ RecoveryIntent
   ▼
RECOVERY_PENDING + FROZEN
   │
   │ approvals + delay
   ▼
NORMAL
with replacement credential
```

During recovery:

```text
normal spend            DENIED
new sessions            DENIED
credential removal      DENIED
auth weakening          DENIED
recovery completion     ALLOWED
```

Recovery cancellation requires an explicitly defined policy.

---

## 20. Emergency Freeze

Kavach SHOULD support emergency freeze independently of full recovery.

```text
Device compromised
       │
Guardian / FreezePolicy
       │
       ▼
Kavach account frozen
```

Frozen mode MUST prevent:

* ordinary spending;
* session spending;
* authorization weakening;
* credential removal.

V1 permits only recovery initiation and explicitly authorized unfreeze from `Frozen`. `RecoveryPending` permits only completion or cancellation under section 26. General administrative changes remain blocked.

A confirmed freeze increments `stateVersion`, invalidating outstanding intents bound to the old version. It cannot reverse an already accepted spend or guarantee priority over a competing transaction.

---

## 21. Device Authentication

Kavach MUST NOT assume every device uses the same cryptographic algorithm. The following are future integration examples, not promised V1 capabilities. Direct P-256/passkey support requires verified ledger/compiler support and affordable verification; WebAuthn also requires correct challenge, origin/RP, authenticator-data and encoding validation. A remote signer using passkeys off-chain is a different trust model from direct on-chain passkey verification.

Potential credentials include:

```text
iPhone
    Secure Enclave / P-256

Hardware wallet
    Ed25519

Remote HSM/KMS
    Ed25519

Future device
    passkey
```

---

## 22. V1 Scope and Non-Goals

The initial proposal describes a broader roadmap than the first deployable protocol. V1 deliberately limits transaction shapes so its safety properties can be reviewed.

| Area | Proposed V1 | Deferred / separate ADR |
| --- | --- | --- |
| Account identity | One-shot state NFT; immutable core binding | Closure, NFT burn, core migration |
| Spending | One account, one intent, exact nonempty account-input set; ADA/native-asset transfers | Anchor selection, multiple accounts/intents, arbitrary dApp calls |
| Authentication | Ed25519 intent signatures; distinct role policies with unique credentials and bounded thresholds | Direct passkeys/P-256, BLS, new curves, arbitrary policy trees |
| Administration | Credential/configuration rotation and compatible auth-module replacement | Automatic upgrade registry or protocol-wide administrator |
| Safety | Freeze/unfreeze and delayed recovery | Session keys, recurring payments, cumulative spending limits |
| State access | Read-only canonical state for spend; consume/recreate for mutation | Sharded or optimistic mutable state |
| Ledger actions | Transfers plus required checkpoint setup/zero withdrawals | Account staking, rewards, governance, token issuance/burning |
| Integrations | Java SDK and reference Yano flow; CIP-113 compatibility prototype before core freeze | Full CIP-113 support per ADR-002; full wallet/dApp compatibility layer |

Unsupported operations MUST fail closed. Mint/burn fields and certificates/governance actions are rejected in normal V1 spends; initialization permits only the explicitly specified NFT mint and necessary checkpoint setup. Exact input binding restricts **account** inputs, allowing independent sponsor fee inputs. No protocol administrator, hosted service or Yano component receives implicit authority over accounts.

Ed25519 signing of a Kavach intent and a Cardano transaction witness are different authorization schemes. `TxInfo.signatories` is not evidence of a detached intent signature. A future transaction-witness module needs its own scheme identifier and tests, and must preserve all core intent checks.

Cumulative limits cannot be enforced by reading immutable reference state alone: concurrent spends could each pass the same check. Such policies require consumed budget state or capabilities and a separate concurrency design. Per-transaction limits are different and may be expressed by the signed transfer itself.

### 22.1 CIP-113 compatibility planning

[ADR-002](adr-002-kavach-cip113-interoperability.md) specifies a separate account-specific owner adapter for tokens held under a CIP-113 payment script. The ordinary account address and accounting rules in this ADR remain the ordinary-transfer profile; they do not automatically cover externally held programmable tokens. The adapter must enforce equivalent Kavach safety checks for its own custody domain.

Before production core scripts and state encoding are frozen, prove adapter deployment against an existing account without changing its `AccountId` or ordinary/state core hashes. Freeze a typed, operation-specific authorization boundary; never add unrestricted external-script approval. A compatible auth-module upgrade may be needed to enable the later operation. Full integration is deferred until ADR-002's gates pass.

Use the Cardano Foundation platform as the interoperability reference and cardano-client-lib PR #657 as the Java SDK integration candidate, qualified against their exact contract artifacts as recorded in ADR-002. The candidate upstream implementation has separate transfer/third-party/restructuring delegates and mutable protocol parameters; stable custody addresses do not remove upstream governance or issuer risks.

## 23. Account Creation, Addresses and State Integrity

### 23.1 Proposed script dependency layout

Avoid circular script-hash parameterization. A candidate deployment order is:

1. Compile the generic state validator and stable core checkpoint.
2. Parameterize the one-shot minting policy with a consumed seed `TxOutRef`, the state-validator hash and deployment domain. Fix its single asset name; derive `AccountId` from the applied policy hash and name.
3. Parameterize the asset validator with `AccountId`, the state-validator hash, core checkpoint hash and deployment domain. Derive its enterprise script address (no stake credential in V1).
4. Build the initial state containing those bindings and the independently compiled initial auth module/configuration. The minting policy authenticates this exact initial-state commitment and designated creator authorization through its specified creation redeemer. Consuming the seed also requires its ledger spending authorization.

The policy MUST verify the derived asset binding using a proven construction or an explicit creator-authorized commitment; it must not accept a relayer-selected address. Final parameter application, creator authorization and hash derivation are Phase 0 specifications, with golden deployment vectors. No script may require a hash which recursively depends on its own hash.

A shared state-validator address is possible because the NFT identifies each state. The asset validator is account-specific so ownership does not rely on a depositor-supplied datum. Authentication changes preserve this address. A new core script changes the address and requires a separately designed asset migration.

### 23.2 Mint and continuation invariants

Creation MUST consume the unique seed and mint exactly one unit of the fixed asset name, with no other names under that policy. It MUST create exactly one output at the expected state validator containing that NFT and a well-formed initial inline datum: version and recovery sequence zero, `Normal` mode, valid core/domain binding, valid role configuration and bounded recovery delay. Initial configuration validation must execute under the selected module; opaque bytes cannot be assumed valid. Further minting and all burning are forbidden in V1.

Every state transition MUST consume exactly one authenticated state input and create exactly one successor at the same full state address. It preserves the NFT, account identity, deployment domain and core binding, increments `stateVersion` by exactly one, and rejects unsupported schema versions, invalid counters and unauthorized field changes. The state NFT may never become ordinary account change or a recipient asset.

The state UTxO is reserved for its NFT and lovelace deposit. Transitions preserve or increase its lovelace; fees and datum-growth top-ups come from external funding. Normal account assets reside at the asset validator. An output at the state address without the NFT is not state. Unsolicited assets accidentally sent there have no promised recovery path in V1; clients must expose the asset address for deposits.

Inline state data is required for deterministic discovery and decoding; ledger output-size/minimum-ADA rules still apply. [CIP-32](https://cips.cardano.org/cip/CIP-0032) describes inline datums. Referencing state does not execute its spending validator, so each consuming authorization path must authenticate the NFT and state binding itself. [CIP-31](https://cips.cardano.org/cip/CIP-0031) specifies this reference-input behavior.

### 23.3 Concurrency and availability

Disjoint asset spends can reference one state concurrently. State mutation consumes that reference and makes transactions built against it stale. Competing operations require rebuilding against the confirmed state; clients must handle rollback and reorgs. No global spend counter is claimed.

State lookup/indexers are discovery services, not authorities. Resolve the complete NFT identity and verify the returned UTxO and datum before signing. Anyone may deposit to the asset address; deposit acceptance does not execute the validator. The asset validator must support spending such outputs without requiring a depositor-controlled datum schema. Account change uses the full canonical address and the V1 output form (no datum or reference script); other output shapes must not be credited as safe change.

## 24. Exact Spending and Value Accounting

The V1 core MUST establish all of the following independently of module approval:

1. The canonical referenced state is `Normal`; its version and immutable bindings equal the intent domain.
2. The signed input list is nonempty, duplicate-free and canonically ordered. It equals the complete set of consumed inputs with this account's asset payment credential, not merely a subset. Every such input points to the same checkpoint intent digest.
3. The transaction validity interval is finite, nonempty and wholly contained in the signed validity interval. Inclusive/exclusive boundaries must be specified and tested; interval overlap is insufficient.
4. Each signed recipient allocation identifies a unique output index, exact full address, exact multi-asset value, and required datum/reference-script form. V1 plain transfers require no datum or reference script. Self-transfers use a separate future operation; recipient outputs cannot also count as account change.
5. Every account-change output uses the immutable full account address and supported output form. Return to the same payment hash with an unexpected stake credential is not acceptable change.
6. No state NFT is spent as an ordinary asset. Unsupported ledger operations and other Kavach account spends are rejected.

For each asset `a`, define `I[a]` as all consumed account asset value, `R[a]` as the signed recipient allocations, and `C[a]` as all qualifying account change. The core enforces:

```text
For every non-ADA asset a: I[a] = R[a] + C[a]
For lovelace:             I[ADA] = R[ADA] + C[ADA] + F
                         0 <= F <= maxAccountFeeLovelace
                         F <= transaction fee
```

Every quantity and fee bound must be nonnegative. Compare the complete asset maps, including assets absent from the intent; do not check only named tokens. Reference-input value and state deposits are excluded. Recipient min-ADA must be included in the signed allocation. The sponsor pays the remaining fee; V1 has no implicit relayer tip. Any future service charge must be an explicit signed recipient allocation.

Because assets are fungible, this defines a bound on net account debit; it cannot prove which physical lovelace paid a network fee when sponsor inputs coexist. The stated invariant is that all account debit beyond exact recipients and safe change is at most the authorized fee contribution. Output indices are signed in V1, sacrificing relayer output-order flexibility for unambiguous allocation.

External sponsor inputs, their change and collateral require their own ledger authorization. Kavach does not promise to protect a sponsor who signs an unsafe transaction. The builder must validate the complete balanced transaction and keep account assets out of collateral arrangements.

## 25. Language-Independent Wire Format and Conformance

The protocol specification MUST be defined independently of Java records, JuLC implementation details, or any future Aiken data declarations. Source-language types are implementations of the schema, not its authority. Before producing signing or deployment artifacts, publish a versioned normative schema for:

- `AccountId`, deployment/core domain, state, mode, module reference and configuration envelope;
- every intent variant, action tag, validity bound and replay binding;
- checkpoint redeemers, module evidence, recovery proposals and policy/credential identifiers;
- integer ranges, byte lengths, constructor indices, field order, list ordering, duplicates, optional fields and unknown-version rejection.

The proposed digest is `blake2b_256(serialiseData(IntentEnvelope))`, with a fixed byte-string protocol tag and all domain fields **inside** the typed envelope. Phase 0 must freeze the exact encoding and digest procedure and confirm support on the target ledger. No ad-hoc JSON serialization, platform object serialization, ambiguous concatenation, or unsigned parallel message field is allowed.

Canonical values use sorted, duplicate-free asset entries and input references; prohibit ambiguous encodings of equivalent maps or omitted fields. The specification must distinguish accepted external CBOR from the normalized on-chain Data reserialization used for signing. Define schema-version migration explicitly; reordering Java fields or sealed variants must not silently change published protocol bytes.

Publish language-neutral fixtures containing typed field descriptions, encoded Data/CBOR, signing preimage, digest, public key, signature and expected outcome. Include malformed encodings, wrong domains, boundary values and rejection vectors. Java SDK and compiled JuLC checks must agree on every fixture. Future Aiken implementations must pass the same fixtures and transaction/state-machine conformance suite.

Conformance means equivalent acceptance and rejection behavior under specified ledger conditions. It does **not** imply identical compiled script bytes, hashes or addresses. A differently compiled implementation represents a distinct deployment; migration and domain binding remain explicit.

## 26. Recovery and Freeze State Machine

V1 uses a single pending recovery proposal. Policies below are separate operation authorities, even if an account deliberately assigns some overlapping credentials. Distinct credential IDs must resolve to distinct authorized keys when counted toward a threshold; repeated evidence or aliases for the same key cannot multiply votes.

| Current mode | Operation | Required authority / condition | Successor mode |
| --- | --- | --- | --- |
| Normal | Spend | Spend policy; exact spend rules | Normal; state referenced |
| Normal | Credential/policy/module change | Old AdminPolicy; valid successor configuration | Normal |
| Normal | Freeze | FreezePolicy | Frozen |
| Frozen | Unfreeze | Explicit UnfreezePolicy; stronger than everyday spend authority | Normal |
| Normal or Frozen | StartRecovery | Current RecoveryPolicy threshold; exact target commitment | RecoveryPending |
| RecoveryPending | CompleteRecovery | Stored proposal, delay elapsed, target proof of possession | Normal with committed replacement |
| RecoveryPending | CancelRecovery | Explicit RecoveryCancelPolicy | Frozen |

V1 configuration changes cannot disable recovery or reduce its configured delay; these are explicit core field constraints. Compatible modules must reject empty or invalid role policies and retain supported recovery behavior. Because module configuration is opaque, module review must establish that requirement.

All other transitions are rejected in V1. In particular, frozen/pending accounts cannot spend, install sessions, perform general configuration changes, or use ordinary unfreeze to bypass a pending recovery. V1 does not provide an underspecified “security-preserving admin” escape hatch.

`StartRecovery` consumes the old state, increments both `stateVersion` and `recoverySequence`, and stores the new attempt number. The authorized proposal commits to the complete replacement configuration, the unchanged module reference in V1, old state version, account/domain and sequence. Recovery changes credentials within the installed module; replacement of the module during recovery is deferred. The delay comes from old authenticated state and cannot be lowered by the proposal.

A validator cannot observe transaction inclusion time directly. Initiation therefore requires a bounded finite validity interval and sets `executeAfter = initiation upper bound + configured delay`. This conservatively starts the delay no earlier than any allowed initiation time. The permitted maximum interval width and delay range must be fixed and tested before V1.

`CompleteRecovery` requires a finite validity interval whose lower bound is at or after `executeAfter`, equality of the stored proposal commitment and supplied target, and an installed-module verification of target proof of possession for that exact proposal. It requires no fresh approval from the lost everyday credential. The old state already records recovery-threshold approval; target proof prevents installation of unusable or mistyped credentials. Finalization increments `stateVersion`, preserves the attempt sequence and clears the pending proposal. Validators must never allow target evidence to select a different replacement.

Cancellation increments `stateVersion`, preserves the monotonically increasing attempt sequence, clears the proposal and remains frozen. A later attempt increments the sequence again. Numbering advances at initiation, including attempts later cancelled, so cancelled evidence cannot be reused. No automatic expiry silently unfreezes funds.

The default first module must define explicit initiation, cancellation and unfreeze thresholds, configuration bounds, target-proof rules, and how its existing recovery configuration is retained or rotated. No timeout, administrator or service may bypass those policies. If an installed module is broken or all recovery authority is lost, this architecture may be unable to recover; independent recovery modules are a separate design decision, not an implied guarantee.

## 27. Trust Model, Threats and Upgrade Limits

Adversaries may control relayers, transaction construction, dApp requests, indexer responses, arbitrary outputs/datums, or some credentials. Assume ledger validation and the selected cryptographic primitives behave as specified. Compiler correctness, serialization correctness and module correctness remain part of the trusted implementation and require validation.

| Threat | Required defense / residual limitation |
| --- | --- |
| Fake state or malicious initialization | Full NFT identity, one-shot supply, state address/schema and creator-bound initial configuration |
| Module executes for another action/account | Exact credential, purpose and account/version/operation/digest binding |
| Malicious relayer drains extra inputs | Exact full input set, exact recipients and per-asset conservation |
| One payout satisfies several spends | Single-account/single-intent restriction plus disjoint output allocation |
| Duplicate guardian votes | Unique keys and bounded threshold evaluation |
| Old or foreign signature reused | Deployment/account/action domain, state version, consumed replay resource, validity bounds |
| Recovery target substitution or early completion | Stored full proposal commitment and conservative interval checks |
| Primary-key compromise | Limited role authority and confirmed freeze; spending can still win the race |
| Oversized datum/policy/evidence | Protocol size/count/depth bounds and worst-case budget tests |
| Malicious authorized module upgrade | Old AdminPolicy plus explicit target commitment; module review remains necessary |
| Signer service compromised/unavailable | Explicit custody/availability assumptions; no implicit trust in OAuth or device UX |

The core cannot inspect arbitrary module code to prove it is safe. A malicious module installed through valid administration can grant arbitrary spend authorization, even though core accounting still applies. A defective module may permanently lock an account, including recovery. Compatible module ABI versions, configuration validation and documented review are necessary; no global allowlist is assumed.

Core code is immutable in V1. Updating `authModuleVersion` is a data change, not proof of compatible behavior. Upgrading JuLC, changing compiler flags, or rewriting in Aiken can change script hashes. An upgrade must not silently strand existing assets. Core migration, emergency escape routes and closure/burn require a new ADR with explicit authority, delay, identity and asset-movement rules.

All on-chain configuration, public keys, intents and recovery activity are public. Do not store personal identifiers, OAuth tokens or private authentication material in datums. Hashing low-entropy personal data does not ensure privacy.

## 28. Initial JuLC / Gradle Implementation Plan

Use Java package root and Gradle group `com.bloxbean.cardano.kavach`. The initial baseline is a Java 25 toolchain and pinned compatible Gradle 9.x wrapper, following the [JuLC Gradle setup](https://julc.dev/getting-started/). Pin one tested JuLC dependency set and the off-chain client-library version during scaffolding; no release version is selected by this ADR.

Suggested modules, introduced only when needed:

| Module | Responsibility | Package suffix |
| --- | --- | --- |
| `kavach-protocol` | Typed wire schema and encoding adapters; no wallet/service dependencies | `.protocol` |
| `kavach-contracts` | State NFT policy, state/asset validators, core checkpoint | `.contracts` |
| `kavach-auth-ed25519` | First authorization module and bounded role policies | `.auth.ed25519` |
| `kavach-sdk` | Intent construction, state discovery, balancing/submission adapters | `.sdk` |
| `kavach-conformance` | Language-neutral fixtures, adversarial transactions, reference state model | `.conformance` |

Keep protocol specifications and fixtures outside language-specific generated code. Contract modules may share compatible types but must not depend on off-chain networking, storage or wallet implementation. JuLC annotation processing produces deployed scripts; test those artifacts rather than assuming normal JVM execution is equivalent. Consult the [AI starter pack](https://julc.dev/ai/starter-pack/) for supported constructs and opaque-data boundaries.

Record compiler and dependency versions, parameter values, optimization settings, script hashes, blueprints, network/protocol parameters and execution budgets in reproducible deployment manifests. A clean build must reproduce deployment artifacts. Do not select newer builtins merely because a library exposes them; prove availability in the intended ledger environment.

## 29. Validation Gates and Delivery Phases

### Phase 0 — specification and feasibility, no real funds

Freeze the wire schema and threat model; demonstrate acyclic parameterization, one-shot initialization, first-module signatures, purpose/redeemer binding and zero-withdrawal ledger acceptance. Specify numeric bounds for inputs, outputs, assets, configuration bytes, credentials, evidence, validity width and recovery delay. Measure worst-case CPU, memory, script/transaction size and minimum ADA against the chosen network's protocol parameters. If the checkpoint optimization does not pass, choose the simpler proven path. Complete ADR-002's bounded CIP-113 adapter compatibility prototype before freezing production core hashes or claiming later integration can preserve them.

### Phase 1 — minimal transfer protocol

Implement creation, exact-input spends, canonical Java encoding and the first module. Require positive and adversarial tests through compiled UPLC, including malformed data, incorrect state NFT/address, missing or wrong checkpoints, wrong operation/domain, replay, missing inputs, output reuse, unexpected native assets and excessive fee debit. A node-valid transaction test must complement constructed script contexts.

### Phase 2 — administration and recovery

Implement all and only the state-machine transitions above. Test stale state, version/sequence boundaries, threshold duplicates, invalid successor configs, self-approved upgrades, forbidden field changes, cancelled-proposal replay, wrong target, exact delay boundaries and competing freeze/spend transactions. Model-based/property testing must show that accepted transition sequences preserve identity, NFT supply, role boundaries and value constraints.

### Phase 3 — independent review and integration

Use the [JuLC testing guide](https://julc.dev/guides/testing-guide/) for compiled evaluation and budget assertions. Where supported, compare independent UPLC evaluators; agreement adds evidence but does not prove correctness. Test real transaction construction and submission on a controlled test network, including unavailable reference scripts, insufficient deposits/fees, stale references and rollback handling.

Before real-value use, obtain independent security review, resolve findings, publish conformance vectors and deployment artifacts, and document residual trust/availability risks. Exercise Yano as a reference integration while keeping protocol tests wallet-independent. A future Aiken implementation must pass the same conformance suite and receive its own implementation review.

No phase is complete merely because Java compiles or happy-path tests pass. Contract authorization failures must be demonstrated as rejection of the deployed UPLC, and ledger-dependent claims as acceptance/rejection by ledger validation.

## 30. Consequences, Alternatives and Open Decisions

The split state/asset design permits concurrent disjoint spending and credential rotation without moving assets, but adds state discovery and stale-reference handling. Separate stable and replaceable checkpoints preserve the core trust boundary at the cost of more scripts and transaction assembly complexity. Exact input/output binding and a narrow V1 reduce ambiguity while limiting relayer flexibility and dApp composability.

Alternatives considered:

- **Key-only/native multisig wallet:** simpler, but does not provide the proposed replaceable semantic authorization and recovery lifecycle.
- **Consume state on every spend:** straightforward serialization and spending counters; retained as a possible future policy choice, with account-wide contention.
- **Put all assets in the state UTxO:** simple initial model but serializes every spend and grows a single output's value footprint.
- **Upgradeable module owns every check:** fewer immutable checks, but allows an installed module to remove replay, lifecycle and accounting rules; rejected.
- **Repeat checks per input:** potentially higher cost but acceptable if it is the simpler proven implementation; performance is secondary.

Open decisions must be resolved with evidence before their dependent phase is complete:

| Decision | Gate |
| --- | --- |
| Final script parameter graph and creator-bound initialization protocol | Phase 0 deployment vectors and adversarial mint tests |
| Exact wire tags, CBOR normalization, domain and signature scheme | Phase 0 cross-boundary golden vectors |
| Checkpoint registration, setup cost, deregistration protection and fallback | Phase 0 ledger-valid prototype |
| Numeric bounds and initial recovery/unfreeze/cancellation defaults | Phase 0 specification; Phase 2 adversarial enforcement |
| Exact module ABI and successor-config/target-possession validation | Phase 0 specification; Phase 2 upgrade and recovery tests |
| Dependency versions, compiler flags and target protocol parameters | Phase 0 reproducibility and budget report |
| CIP-113 owner-adapter ABI and preservation of existing core hashes | ADR-002 Phase 0 prototype; full integration deferred |
| Core migration, independent recovery modules, passkeys, staking and dApp operations | Separate ADRs; outside V1 |

### References and evidence status

External sources reviewed on 2026-09-06 describe tooling and ledger mechanisms, not a security validation of Kavach. The composition and requirements above are Kavach design proposals.

- [JuLC AI starter pack](https://julc.dev/ai/starter-pack/): authoring constraints and typed contract boundaries.
- [JuLC getting started](https://julc.dev/getting-started/): Gradle setup, compilation and deployment artifacts.
- [JuLC testing guide](https://julc.dev/guides/testing-guide/): UPLC evaluation and budget testing.
- [CIP-31 — Reference inputs](https://cips.cardano.org/cip/CIP-0031): read-only state access and its validation limitations.
- [CIP-32 — Inline datums](https://cips.cardano.org/cip/CIP-0032): state-data availability.
- [CIP-33 — Reference scripts](https://cips.cardano.org/cip/CIP-0033): script distribution without treating references as authorization.
- [CIP-112 — Observe Script Type](https://cips.cardano.org/cip/CIP-0112): explanation of the zero-withdrawal pattern; the proposed Observe extension is not assumed available.
