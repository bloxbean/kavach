# Account locator backup

The SDK's version 1 locator is public account-discovery information. Preserve its integrity
and availability alongside the surviving recovery authorities. Guardians alone cannot derive
an account from their keys. The locator does not contain a spending secret or authorize any
transaction; revealing it does reveal which account the backup belongs to.

The text is `kavach-locator-v1:` followed by lowercase hexadecimal of canonical Plutus Data
CBOR, using the same `serialiseData` convention as the protocol. Its Data value is constructor
0 with four fields, in order:

1. Integer version `1`.
2. ADR-001 `AccountId`: full 28-byte state NFT policy and empty token name.
3. `DeploymentDomain`: network ID, network magic and 32-byte deployment discriminator.
4. `CoreBinding`: state, asset and checkpoint validator hashes, in protocol field order.

This is an off-chain SDK format, not an addition to the frozen on-chain CDDL. Unknown versions,
fields, noncanonical encodings, trailing bytes and text longer than 1,024 characters reject.
A future locator format must use an explicit new version. It must not silently reinterpret
old backups.

`AccountLocator.fromState` requires an authenticated state supplied by the caller.
`parse(...).restore(provider)` derives the complete state address, queries the exact NFT and
requires exactly one current claim. It verifies NFT quantity, plain inline state custody,
value shape, reference syntax, supported state decoding and all immutable locator bindings.
It accepts a changed module, configuration, version or mode because those fields are mutable.
It does not need the creation transaction reference or the original device key.

The backend remains a trusted source of current ledger information: these checks are not an
inclusion or freshness proof. `AccountDeployment.restore` fetches V3 scripts by their expected
hashes and checks the bytes match. Hash correspondence is not an independent code audit.
Transaction preparation must revalidate the current state and the ledger must accept the
complete transaction. Independent cancellation/unfreeze or target recovery proofs and a
key-controlled collateral sponsor are still needed.

The short DevKit restoration test uses a new backend client and new fee/collateral account
against the same local ledger, with the old primary key reference discarded. The explicit
long recovery test additionally uses an entirely new target key set and respects the real
minimum delay. Consult the acceptance ledger for whether that long test has completed.
