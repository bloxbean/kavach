# Phase 0: withdrawal and signature feasibility

This is a historical experiment report. See the [qualification report](qualification.md) and [acceptance ledger](completion-checklist.md) for the current Phase 0 status.

Status: **the serialization compiler blocker is fixed locally; the first experiment passes, and Phase 0 remains incomplete**. Recorded 2026-09-06. No real funds, account validator, production deployment or audit claim.

## Scope and implementation

[`WithdrawalProbe`](../../src/main/java/com/bloxbean/cardano/kavach/phase0/WithdrawalProbe.java) is a disposable authorization experiment. An immutable dedicated Ed25519 public key signs `blake2b_256(serialiseData(challenge))`. The challenge contains an immutable experimental domain and a complete consumed `TxOutRef`. The validator requires rewarding purpose, presence of that credential in withdrawals, the signed input among consumed inputs, and canonical constructor/field layout. A reference input is insufficient. Other script purposes reject.

The [CDDL](../../conformance/phase0/withdrawal-v0.cddl) and [public golden vector](../../conformance/phase0/withdrawal-v0.properties) describe **only this experiment**, not the V1 intent ABI. The fixture contains public key, CBOR, digest and signature; its random private key was discarded. Off-chain hashing uses CCL; on-chain verification runs through compiled UPLC. Record-to-Data casts are JuLC representation boundaries and are not intended for JVM execution of the validator.

There is deliberately no account ID/state NFT, state version, recipient/value/fee authorization, core-to-module binding or reward-sink enforcement here. The signature approves only this challenge. It is not suitable for asset custody or signing real transactions. Never send assets to the probe's payment address or use its reward account for rewards. Nonzero reward behavior is unproven; the absence of an amount-zero check is not evidence of safe reward disposition.

## Toolchain and reproducibility

- Java 25; observed OpenJDK 25.0.2.
- Gradle Wrapper 9.2.0, distribution SHA-256 pinned in wrapper properties.
- JuLC compiler, annotation processor, ledger API, testkit and Java VM: `0.1.0-pre17-1a46882-SNAPSHOT`, built from upstream main plus the source-level fix. See [exact provenance, patch and reproduction instructions](../../toolchain/julc/README.md).
- Cardano Client Lib and Blockfrost backend: `0.8.0-pre5`, selected explicitly for the Kavach/CCL integration baseline. Dependency resolution is locked and checked for mixed CCL versions.
- Dependency versions locked in [`gradle.lockfile`](../../gradle.lockfile). Only the exact JuLC snapshot is resolved from Maven local; other dependencies use Maven Central. `verifyJulcToolchain` checks JuLC binary SHA-256 values against the reviewed manifest before compilation.
- Annotation processor defaults: target `plutus-v3-pv11-uplc-1.1.0`, optimization `pv11-safe`; no custom optimizer settings. Parameter order: Ed25519 public key bytes, experimental domain bytes. Plutus V3; local VM evaluation explicitly selects PV11.
- Generated unparameterized probe size reported by annotation processing: **694 bytes**. This is not a deployed transaction size.

The generated `WithdrawalProbe.plutus.json` SHA-256 is `8354044ced77fec77d652fe771d5ddcd91fdce21796085333dec525fd704a93e` (an artifact-file checksum, not a Cardano script hash). Generated artifact checksums are measured per toolchain. The old pre16 artifact checksum does not apply to the source-fixed main snapshot; upgrading the compiler can change script bytes and hashes even when the contract source is unchanged.

```sh
./gradlew clean check
./gradlew integrationTest
./gradlew toolchainAcceptance
```

`check`: **20 conformance tests plus one positive toolchain acceptance test passed**, no skipped tests. All evaluate normally generated compiler output. Negative tests require an actual evaluation failure, not merely budget exhaustion. The former defect-expecting test and AST rewrite have been removed.

`integrationTest`: **one live scenario passed**, with legacy registration, a confirmed withdrawal, a wrong-signature evaluator rejection and a rejected transaction replay. It fails rather than skips if DevKit is unavailable. Each run generates fresh disposable keys and therefore new script hashes and transaction IDs. The faucet supplies 130 test ADA across separate input-owner and fee/collateral accounts. One script registration locks the local stake deposit; no deregistration cleanup is attempted.

`toolchainAcceptance`: **passes** and is now required by `check`. It verifies a valid signature using emitted compiler output without rewriting that output. This clears the observed serialization blocker for this experimental snapshot; it does not establish general compiler correctness or complete Phase 0.

## Findings, corrections and review

1. **The defect persisted on current upstream main.** Both pre16 and main `37a696b2` placed `SerialiseData` in `UplcGenerator.forceCount`'s one-force case. The generated UPLC contained `Force(Builtin(SerialiseData))`, rejected by the Java VM and DevKit evaluator. `SerialiseData` is monomorphic and takes Data directly; its force count must be zero. The source fix removes that entry. A new compiler regression first failed on unmodified main for PV10 and PV11, then passed with the fix; the complete compiler and annotation-processor suites passed 1,485 and 20 tests respectively.

2. **Normal compilation now succeeds.** Fixed local commit `1a46882ec3fdebd057fc71c6e720c14dcdfba36c` was published to Maven local and pinned in Kavach. `SerialiseDataDiagnostic` has been deleted. Kavach now loads the annotation processor's artifact directly for conformance and live tests, with no AST transformation. The [earlier diagnostic evidence](evidence/devkit-pre16-diagnostic-2026-09-06.json) is retained as historical evidence only. The source fix is submitted for review in [JuLC PR #133](https://github.com/bloxbean/julc/pull/133), linked to [issue #132](https://github.com/bloxbean/julc/issues/132); it is not yet merged or released. The [patch and exact source bundle](../../toolchain/julc/README.md) are also preserved locally.

3. **Typed projection is not canonical decoding.** Adversarial tests initially accepted an authorization with the wrong constructor tag or an extra field. Reconstructing the authorization and challenge and comparing their full Data values fixed both cases. Correctly signed malformed challenge records also reject. Equality with the ledger-provided consumed reference binds the nested reference representation. Apply and test equivalent strictness at every future untrusted protocol-data boundary; do not assume the Java type declaration enforces it.

4. **Transaction construction needs independent validation.** An early composition using the same account for explicitly selected inputs and automatic fee funding produced `ValueNotConservedUTxO`. The final harness uses a distinct input owner and fee/collateral sponsor, and requires actual chain confirmation. The evaluation endpoint can assess scripts without proving phase-one value conservation. Negative evaluation tests inspect an explicit evaluator response; a build exception or apparent build success alone is not the result.

5. **Legacy registration works in the observed environment.** The compiled probe reward account was registered without a script witness, then a zero withdrawal was confirmed. The probe rejects certifying purpose in UPLC, including registration and deregistration contexts. This does not prove explicit-deposit registration, live deregistration rejection or re-registration behavior. A future production registration branch must follow ADR-001 rather than copying this reward-only probe.

## Live evidence and budgets

Observed local node: `cardano-node 11.0.1`, `linux-aarch64`, GHC 9.6, revision `97036a66bcf8c89f687ae57a048eecc0389977ef`. Backend protocol parameters report major version 11. Local network configuration uses testnet network ID 0 and magic 42; it is not evidence for another era/network.

The [recorded evidence](evidence/devkit-2026-09-06.json) includes protocol parameters, public script parameters, the compiled script CBOR/hash, transaction IDs, transaction size, execution units, toolchain-manifest/source checksums and failure responses. The DevKit evaluation endpoint is distinguished from the node: registration and the valid withdrawal were submitted and confirmed; wrong-signature rejection was an evaluator result. Replay was a submission rejection.

| Probe | CPU steps | Memory units | Evidence |
| --- | ---: | ---: | --- |
| One consumed input | 85,601,688 | 79,088 | Emitted UPLC, synthetic context, PV11 VM |
| 32 consumed inputs | 173,438,545 | 245,155 | Emitted UPLC, synthetic context, PV11 VM |
| Live compiled withdrawal | 88,435,135 | 84,445 | Execution units of confirmed transaction |

These are small experiment measurements, not worst-case account/module budgets or chosen V1 limits. Synthetic contexts do not establish ledger acceptance. Live test reports are under `build/reports/tests/integrationTest`; fresh public evidence is written to `build/phase0/devkit-evidence.json` only after the entire scenario passes.

## Remaining Phase 0 gates

1. Upstream the source fix and migrate from the local snapshot to a reviewed upstream release when available. The local blocker is cleared, but full toolchain and protocol review remain required before asset custody.
2. Specify the real V1 CDDL and semantic constraints, deterministic signer rendering, numeric limits and full canonical intent vectors. This challenge format does not freeze them.
3. Demonstrate acyclic script parameterization, creator-bound one-shot NFT initialization, unique state authentication, and core/module purpose and redeemer binding.
4. Test empty and positive reward balances, full-balance withdrawal rules and immutable sink disposition; multiple receipts, top-ups and double-counting rejection; explicit-deposit registration, ordering, already-registered failure and re-registration.
5. Implement and measure full-asset conservation, exact input sets, bounded fees, lifecycle transitions and adversarial bounds. Recovery and lost-device discovery are not implemented by this probe.
6. Complete the ADR-002 compatibility experiment or explicitly choose its migration fork before production core-hash freeze. No CIP-113 compatibility claim is made by this milestone.
