# Publisher reference vault, version 1

This standalone Plutus V3 custody protocol implements [ADR-014](../../adr/adr-014-publisher-protected-reference-vault.md).
It is outside the sealed Kavach V1 account schema. It does not modify account scripts,
addresses, authorization modules or account state. This is a development implementation,
not an independent security audit or production qualification.

## Parameters and address identity

Apply exactly three Plutus Data parameters, in this order:

1. `publisherPaymentKeyHash`: bytes of length 28.
2. `purpose`: exactly ASCII `kavach-acc-ref`, hexadecimal `6b61766163682d6163632d726566`.
3. `accountId`: constructor 0 with exactly two fields: NFT policy bytes of length 28,
   followed by NFT asset-name bytes of length 0 through 32.

The canonical identity is the entire NFT asset identifier, including the name. This vault
accepts nonempty names independently of the current account core's empty-name profile.
All widths, the fixed purpose, and the account constructor and arity are enforced on-chain.
The applied script hash commits to the publisher and account. The hosting address is an
enterprise script address with that payment credential and the selected network header;
there is no staking credential. Network ID/magic are not script parameters. Records bind
the network separately; the account policy/name and publisher define the on-chain namespace.

The implementation entry point is `ReferenceVault`; deployment is
`ReferenceVaultDeployment.derive(publisherHash, accountId)`. The helper rejects null or
incorrect-width publisher/account fields with `IllegalArgumentException`.

## Reclaim wire data

The only operation is constructor 0 with exactly two fields:

```text
Reclaim = Constr 0 [ Integer 1, AccountId ]
AccountId = Constr 0 [ Bytes(policy28), Bytes(name0..32) ]
```

The integer is the schema version, not an arbitrary transaction nonce. The complete
redeemer account identifier must equal the immutable account parameter. Constructor tags,
exact arities, nested account fields and types must match. Extra fields, unsupported
versions, other variants or non-constructor data reject.

Canonical client serialization for publisher/policy all zero and empty asset name:

```text
publisher parameter: 581c00000000000000000000000000000000000000000000000000000000
purpose parameter:   4e6b61766163682d6163632d726566
account parameter:   d8799f581c0000000000000000000000000000000000000000000000000000000040ff
reclaim:             d8799f01d8799f581c0000000000000000000000000000000000000000000000000000000040ffff
```

These are Plutus Data CBOR golden vectors, using constructor 0's tag 121 and indefinite
field lists as emitted by the pinned client encoder. The validator checks Data structure;
semantically equivalent ledger-accepted CBOR encodings are not separately forbidden. The
redeemer is not a signed-message format. `ReferenceVaultTest` checks the reclaim vector
and evaluates the actual compiler-emitted UPLC against malformed variants.

## Custody authorization

Only a spending invocation succeeds. The configured publisher payment-key hash must occur
in the transaction's required signatories. Ledger witness validation must establish the
corresponding signature; a fabricated context's signatory field is not evidence of signing.
No datum is required, inspected or trusted. Hosting outputs use no datum. A hosted
reference-script field is not required by the validator, allowing the owner to recover
mistaken deposits. No output, destination, continuing copy, account-holder signature or
replacement proof is required on-chain. The publisher may intentionally reclaim all capital,
including active references. The dashboard adds exact-output/full-value return checks, but
these are not consensus constraints of the custody validator.

No reference script is executed merely because an account transaction references its output.
Normal account operations need no publisher witness. A reclamation transaction supplies the
small custody validator directly as a witness, the Reclaim redeemer, publisher signature,
and suitable separate fee inputs and collateral. Consumed outputs' hosted script bytes must
be included in the ledger's reference-script fee calculation. Reclamation of one exact UTxO
cannot be replayed after it is consumed; no additional replay nonce is required.

Ordinary wallet coin selection excludes this script-address reserve. This does not protect
against a publisher signing a malicious script-spending transaction. Loss of the publisher
key may permanently strand its reserve; account recovery does not replace that key. Removal
of the last usable reference can interrupt account availability until an exact copy is republished.

## Artifact and compatibility requirements

Persist exact applied custody and hosted script bytes, parameter identities, network,
addresses and expected hashes. Untrusted discovery hints are not authorization. Verify
live unspent membership, exact custody address and hosted hash before preparing/revalidating
operations. Existing historical locked or key-hosted outputs retain their existing rules.

Compiler/source changes may change custody hashes. A future implementation must preserve
old vault spending artifacts and parameter verification semantics before changing the
default template. Re-deriving only with a new template cannot safely classify old vaults.
This gate is separate from existing account upgrade rules.

Compiled context tests measure execution and rejection behavior, not full ledger acceptance.
Ledger qualification and paid publication/reclamation fees are recorded separately in the
ADR-014 evidence. No fee reduction for ordinary account transfers is promised by custody.

## Development dashboard API

On the local DevKit dashboard, `POST /api/plans` with JSON prepares (but does not sign or
submit) a reclamation plan:

| Field | Required value |
| --- | --- |
| `action` | `reclaim-reference` |
| `locator` | Canonical retained `kavach-locator-v1:...` text for the vault's AccountId |
| `sponsor` | Connected publisher key address, as supported CIP-30 address hex or Bech32 |
| `transactionHash` | Exact 32-byte transaction hash as lowercase hex |
| `outputIndex` | Exact output index as an integer |
| `scriptHash` | Hosted reference script's 28-byte hash as lowercase hex |
| `acknowledge` | JSON boolean `true`, after reviewing removal consequences |

The API parses the locator's AccountId without requiring live account-state discovery. It
resolves the exact unspent output and verified local vault record, requires matching account
and publisher, and builds an explicit return with separate plain fee funding and collateral.
Thus a retained pre-genesis locator can identify published custody before account creation.
The locator does not authorize reclamation; the immutable publisher key does. Exact hosted
bytes remain necessary for fee calculation, from retained records or verified provider data.

The returned plan includes `reclamation` details and unsigned `transaction`. The publisher
must review and supply valid transaction witnesses through the existing plan witness/submit
flow. Preparation and the `acknowledge` flag alone move no funds. The app rechecks live inputs
and exact returned value at submission. The local public profile directory must be preserved;
the API does not infer unknown vault ownership from arbitrary client-provided labels.

The current UI displays one usable copy per current script hash, not every historical,
duplicate or pre-genesis publication. Use retained output/vault information for exact API/SDK
handling of those copies; no automatic orphan inventory or migration is implemented.
