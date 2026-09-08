# Phase 0: one-shot identity and authenticated state references

This is a historical experiment report. See the [qualification report](qualification.md) and [acceptance ledger](completion-checklist.md) for the current Phase 0 status.

Status: isolated feasibility experiment, 2026-09-07. This is not a usable account, V1 wire-format freeze, audit, or completion of Phase 0. Uses the same [pinned toolchain](../../toolchain/julc/README.md) as the [withdrawal probe](withdrawal-probe.md): JuLC `0.1.0-pre17-1a46882-SNAPSHOT`, CCL `0.8.0-pre5`, Java 25, Plutus V3 / protocol version 11.

## Construction and boundaries

`SealedStateProbe` rejects every spend, including NFT-less outputs. It deliberately removes state mutation from this genesis-authentication experiment. Each successful integration run permanently locks 8 disposable devnet ADA: 4 with the NFT and 4 in an intentionally fake NFT-less state output. The generated keys remain in memory only. Never use this holder for real assets.

The deployment dependency order is acyclic:

1. Compile the generic sealed holder and derive its hash.
2. Parameterize `StateNftMintProbe` with a creator-owned seed input, creator payment key hash, holder hash and experimental deployment domain.
3. Derive the NFT policy ID; its token name is the empty byte string.
4. Parameterize `StateReferenceProbe` with that policy ID, holder hash, domain and creator hash.

Initialization consumes the exact creator-owned seed and requires the creator in ledger required signatories. Minting must contain exactly one entry, quantity one of the expected NFT. Its unique output must carry positive ADA plus that NFT, the exact inline genesis datum, the enterprise holder address, and no reference script. Additional policies/assets, burning, reminting without the seed, foreign stake credentials, hashed datums and malformed genesis state are rejected.

The withdrawal reader checks its rewarding purpose, withdrawal presence and creator required signatory, rejects minting, and authenticates exactly one matching reference input by full NFT identity, quantity, address, value and canonical Data equality. Referencing a holder does not execute its validator. The reader independently authenticates it. It neither consumes a replay resource nor authorizes an account spend; repeated authorized reads are intentional. No positive-reward disposition claim is made.

The [experimental CDDL](../../conformance/phase0/state-identity-v0.cddl) and [public encoding vector](../../conformance/phase0/state-identity-v0.properties) describe only `ProbeState(0, domain, creator)`. They do not define the full account configuration or production factory parameter validation.

## Validation and review

Run `./gradlew check integrationTest`. The identity suite has 33 cases including the encoding fixture, successful compiled execution, missing/foreign seed and signer, burn/excess mint, wrong purpose, fake NFT identity, unexpected address/stake credential, extra assets, zero ADA, wrong domain/creator/version, malformed constructor/field count, hashed/missing datum, reference scripts, duplicate state candidates and consumed-state substitution. The sealed holder rejects both genuine and NFT-less spends. Tests execute normal compiler artifacts without UPLC rewriting.

Synthetic successful contexts consume 107,036,021 CPU / 357,395 memory for minting and 92,834,942 CPU / 306,371 memory for reading. The per-evaluation test ceiling is 500,000,000 CPU / 2,000,000 memory; negative unit cases must fail explicitly rather than exhaust it. These are measured fixtures, not worst-case protocol bounds.

The [live evidence](evidence/state-identity-2026-09-07.json) records public deployment parameters, script CBOR/hashes, source/toolchain hashes, protocol parameters, confirmed mint/registration/read transactions and execution units. It also records cardano-node rejection of an NFT-less state reference and a second mint with a fresh input. Those adversarial transactions keep `isValid=true` and receive an explicit generous test budget; the node must report `ValidationTagMismatch` with `Caused by: error`, rather than accept collateral consumption or merely report a setup error. The genuine state remains unspent after reading.

Review focused on the trust boundary between a genuine datum and a genuine NFT, creator authorization through required signatories, single supply anchored in a consumed input, full value/address authentication, and fail-closed malformed data. The two validators intentionally use independent local records with the same experimental wire shape; their compatibility is exercised by explicit Data fixtures and the real genesis/read flow.

## Findings and remaining gates

The configured DevKit backend uses Scalus evaluation. During QuickTx budget estimation for a reference-input withdrawal, the backend returned HTTP 500 `Error evaluating transaction`. JuLC's full transaction evaluator can complete that construction. The final balanced transaction then passes the same backend evaluator and confirms through cardano-node; the test requires both results. This narrows the unresolved limitation to the provisional construction/evaluation path, rather than establishing that Scalus cannot evaluate reference inputs. Negative cases are decided by cardano-node itself. The initial failure's root cause remains unisolated; generic HTTP errors are never counted as contract rejection evidence.

Direct `Value.inner()` access produced an `UnConstrData` failure on a map in this compiler experiment. The contracts use `ValuesLib.flattenTyped`, explicit asset lookups and `isZero`; successful compiled and ledger tests verify that path. Static `TokenName.EMPTY` in contract source and construction of another validator's nested record were not accepted by this compiler; local supported constructions are used instead. These observations are not upstream fixes or proof of complete API compatibility.

Still required: full AccountState schema and semantic bounds, mutable state transitions and replay rules, checkpoint/module binding, positive rewards and registration-purpose behavior, worst-case budgets, recovery timing/state-machine tests, production creation/configuration validation, and the ADR-002 compatibility-or-migration decision. The permanently sealed holder cannot substitute for those designs.
