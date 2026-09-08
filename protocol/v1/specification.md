# Kavach ordinary-account candidate profile v1.0-rc1

This document and `kavach.cddl` define the candidate ordinary-account wire contract for Phase 0 qualification. ADR-001 supplies the security requirements. A candidate is not a deployment approval: the acceptance ledger must establish its encoding, bindings and maximum-size feasibility before the profile is frozen. No implementation may advertise conformance merely by decoding these records.

## Encoding and identities

Record constructor is 0; variant constructors and exact field counts are in CDDL. Reject unknown versions/tags, extra/missing fields, malformed primitive types, duplicate keys/IDs, negative unsigned fields and values outside semantic ranges. Canonical signing preimages use Cardano `serialiseData`, including its list/constructor-array and byte-string chunk conventions, not generic deterministic CBOR. External equivalent CBOR may decode to the same Data, but signing always rebuilds the typed canonical Data and reserializes it. No map appears in core typed records; opaque module configurations must normalize under their module schema before use. The CDDL `data` rule selects the initial Ed25519 configuration profile; future module variants require their own complete schema and qualification.

The state NFT name is empty and supply exactly one. Account identity is its complete policy/name pair. Network ID and network magic are deployment parameters; Plutus cannot discover network magic from TxInfo. Every deployment uses a fresh random 32-byte public discriminator fixed before scripts are applied. Mainnet uses network ID 1 and magic 764824073; devnet uses 0 and its actual magic. Cloned histories with identical deployment parameters are not separable by signatures.

The exact ordered parameters are specified in [deployment.md](deployment.md). Deployment order: generic state validator and sink-parameterized core checkpoint; mint policy parameterized only by core version, seed, creator, state-validator hash and deployment domain; AccountId; asset validator applied to AccountId and state/checkpoint/domain; final genesis state with creator-authorized derived binding. The final genesis state is committed by the creator-signed initialization transaction (or a separately domain-bound mint redeemer proof), after AccountId and the asset hash are derived. That commitment MUST NOT be a mint-policy parameter containing the policy's own derived AccountId: doing so would recreate a hash cycle. It covers all final fields and never accepts an unauthenticated relayer-chosen address. Final deployment artifacts and vectors remain a qualification gate.

An `Address` is the full Plutus ledger payment/stake structure. Canonical account and state addresses are enterprise script addresses; receipts use immutable full key addresses. Off-chain builders also enforce the network header. Recipients may be supported key or script addresses but have no datum/reference script under the plain-transfer profile. Nominal recipients back to the source account reject.

## Numeric profile

These candidate maxima apply jointly, including the serialized-byte caps; a collection satisfying each count bound can still exceed its byte cap. The supported set is their intersection. Qualification must cover the largest permitted serialized shapes before freeze. Exceeding a bound rejects; there is no truncation or automatic splitting after signing.

| Field | Candidate bound |
| --- | --- |
| Total ordinary transaction inputs / reference inputs / outputs | 16 / 4 / 16 |
| Account inputs / recipient allocations | 8 / 8 |
| Distinct assets in the union of account inputs, recipients and change | 12 including ADA |
| Assets per value | 12 including ADA |
| Value quantity / aggregate quantity / state version / recovery sequence | 0 .. 2^63-1; reject overflow before successor increment |
| Output index in signed allocations | 0 .. 15 and within actual output list |
| Native policy ID / asset name | Exactly 28 / 0..32 bytes |
| ADA asset identifier | Empty policy and empty name only |
| Config / intent serialized Data | 1024 / 1536 bytes |
| Normal/Frozen state / RecoveryPending state serialized Data | 1536 / 3072 bytes |
| Total native-asset entries across account inputs / transaction outputs | 12 / 12 (ADA excluded from these entry counts) |
| Registry public keys / keys in a role / signatures per proof | 16 / 8 / 8 |
| Required module/core/successor withdrawals / receipt allocations | 3 / 3 |
| Configuration possession proofs / key transaction witnesses | 16 / 16 |
| Intent validity width | 1 .. 300,000 milliseconds |
| Recovery delay | 86,400,000 .. 7,776,000,000 milliseconds (1..90 days) |
| Recovery cooldown | 3,600,000 .. 2,592,000,000 milliseconds (1 hour..30 days) |
| Timestamps / computed deadlines | 0 .. 2^63-1; checked addition |
| Signed account fee contribution | 0 .. 5,000,000 lovelace and no more than actual fee |

Native-asset entries require positive quantity, sort unsigned lexicographically by policy bytes then asset-name bytes, and have no duplicates. ADA is first, positive for output values; zero entries are omitted. Input references sort unsigned transaction-ID bytes then numeric index, without duplicates. Recipients sort strictly by output index. Credential IDs and proofs sort strictly numerically; credential aliases resolving to the same key reject even across the registry. Ed25519 public keys must have canonical, nonidentity prime-order points; the off-chain decoder performs full point validation. The on-chain successor/target validation must also establish usable proof of possession rather than trusting a byte-length check alone. Receipts sort by ledger credential order and have unique output indices. Whole-transaction bounds include sponsor inputs/outputs: relayers cannot evade limits through unrelated entries.

## Authorities and lifecycle

The first module uses raw Ed25519 over the 32-byte canonical envelope digest, scheme 0. It maps Spend/TransferWholeUtxo→spend, ReplaceConfig/ReplaceModule→admin, Freeze→freeze, Unfreeze→unfreeze, StartRecovery→recovery, CancelRecovery→cancel. CompleteRecovery uses separately domain-separated target proof of possession under the committed replacement spend policy; the stored initiation approval supplies recovery authority. It never needs the lost old spend key.

Recovery keys must be disjoint from cancellation and unfreeze keys. Both defensive key sets must also exclude everyday spend keys. Cancellation/unfreeze may overlap each other. The reference profile requires administration stronger than unilateral everyday spending; it must not silently configure an everyday key as standalone administrator. Remote-signer use in the reference profile requires joint approval with a user-held key, not an AnyOf route granting unilateral custody. Module successor-configuration validation cannot authorize its own installation: the old module must approve the complete replacement.

Validity intervals are half-open `[notBefore, expiresAt)`, nonempty and finite. The transaction's effective POSIX interval must be contained in it, with its actual bound inclusivity respected. Start/cancel transactions have finite width ≤300,000ms. Use the transaction upper endpoint to calculate conservative execution/cooldown deadlines; use the lower endpoint to prove an existing deadline elapsed. Timestamp arithmetic is bounded and never wraps.

State version increments on every state mutation. Sequence increments only on StartRecovery and is never duplicated in pending state. Initialization uses version/sequence/recoveryNotBefore zero. Starting recovery commits to the current account/domain/module/version, incremented sequence, full replacement config and old delay/cooldown. Store its digest and the full target configuration in RecoveryPending, with executeAfter = upper bound + old delay. Update recoveryNotBefore to max(old, upper bound + old cooldown). Cancellation clears pending mode to Frozen, preserves sequence, increments version and extends the same monotonic deadline. Completion requires lower ≥ executeAfter, byte-for-byte canonical equality with the stored target config, and target proof bound to the stored proposal commitment; it changes mode to Normal. Unfreeze requires defensive policy and cannot bypass pending recovery. Other mutations preserve the cooldown deadline. Recovery cannot change module, omit supported recovery, or bypass independent defensive roles. This initial wire profile carries no timing-update fields: every V1 state successor preserves the creation-time delay and cooldown exactly. An unsigned increase is not permitted merely because it is nondecreasing.

The pending commitment uses `blake2b_256(serialiseData([intent domain, next sequence, module ref, replacement config, old delay, old cooldown]))` as constructor 0 with exactly six fields. The target proof tag is UTF-8 `KAVACH_RECOVERY_TARGET_V1`; target proof binds that commitment and replacement-config digest. It is not interchangeable with a spend or initiation signature.

## Module execution and receipts

Core resolves the current state from the complete NFT identity/address/schema. It derives the canonical envelope digest itself. It requires the configured module's script credential in withdrawals and the redeemer under the exact Rewarding purpose. Decode the module redeemer, reconstruct canonically, and require equality of envelope, account/version/action/digest and receipt table. A certifying invocation, a reference-script attachment, or an unrelated module withdrawal cannot satisfy authorization. The called module independently authenticates the same state and recomputes the digest. No generic external-operation tag is reserved in ordinary V1.

The exact invocation and configuration/target proof domains are specified in [module-abi.md](module-abi.md). Core and module have distinct reward credentials. A positive required withdrawal has exactly one receipt at its immutable sink; its value is ADA only and at least the withdrawn amount, with no datum/reference script. Receipt indices are unique across all required credentials, disjoint from signed recipients, account change and the state successor. Zero withdrawals have no receipt entry. Shared sinks do not permit shared output indices. Extra receipt entries reject. Rewards and receipts never participate in account-input conservation or signed fee contribution. External sponsors supply any minimum-ADA top-up.

## Deterministic signing display

Decode once into the canonical typed envelope inside the signer, validate it, render every field and derive the digest from that same object. Display protocol/deployment/network, full AccountId/core hashes/state reference/version, action name, validity endpoints with inclusivity, exact input references, each output index/address/policy/name/integer quantity, and fee limit. For configuration actions, enumerate keys and each role/threshold; for recovery, show sequence, target configuration, delay/cooldown and proposal commitment. Never replace raw identifiers with ticker/decimal metadata. Render bytes as lowercase hex, integers as decimal, and ordered lists in their canonical order. Display the complete digest for independent comparison. Resolve and authenticate the signed state reference before signing; `WireFormat.renderSigningRequest` binds its domain/account/core/version/reference and appends the full current configuration, mode, recovery delay/cooldown and pending proposal. The caller must independently verify the resolved UTxO address/NFT and network; a matching datum alone is not authentication. `renderIntent` alone is the canonical envelope rendering, not a complete recovery approval display. Locale formatting is supplementary; signer policy must inspect typed fields, not a client-provided opaque hash or display string.

Normative machine rendering is a UTF-8 line sequence with stable field paths (`domain.account.policy`, `action.recipients[0].value[0].quantity`, etc.), `=` separators, lowercase hex and base-10 integers, ending in LF. Values may not contain unescaped newlines or control characters; addresses are rendered by their typed credentials to avoid HRP/network ambiguity. Golden fixtures must cover every action and all security-relevant fields before freeze. A compromised signer/display remains outside what on-chain validation can attest.

## Whole-UTxO transfer

Action 8 (`TransferWholeUtxo`, CBOR constructor tag 1281) binds exactly one account input, one recipient output index, a full key-payment recipient address and the digest of the input's canonical Plutus ledger Value. It uses ordinary spend authority and Normal mode. Core independently matches the actual consumed input to the signed reference and value digest. No second account input, other script input or account-change output is allowed. The state NFT remains a reference input.

The recipient has no datum/reference script. Its complete native-asset map must equal the input's; its ADA must be at least the input's. No account-funded fee is allowed. Sponsors fund fees and any ADA top-up, including a changed minimum-ADA requirement. All non-recipient outputs must be plain ADA-only key outputs (including sponsor change and reward receipts), with no datum/reference script. Reward receipts cannot share the recipient index. Preserve the full maps, including unrecognized tokens. The 12-asset partial-transfer limits do not apply to this one input and recipient; the serialized input Plutus Value must be at most 8192 bytes, covering the qualified 5000-byte ledger Value limit. This path supports quantities representable by the ledger (the resolver accepts positive unsigned 64-bit quantities), without aggregate arithmetic. Native-asset mint/burn, certificates, governance and dApp calls remain excluded.

Signing requires resolving the exact input's Value, validating canonical policy/name ordering, re-hashing it, and displaying all entries. `WireFormat.renderIntent(intent, resolvedInputValue)` enforces this; the overload without a resolved value rejects action 8. The resolver is bounded to 2048 entries and 8192 encoded bytes. The reference signer must additionally verify that the input is at the expected account address; on-chain core checks that independently. The ordinary intent digest still covers the entire typed envelope and its value digest. A generic opaque digest or a relayer-provided display string is never accepted.
