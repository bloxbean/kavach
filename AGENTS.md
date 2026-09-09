# Kavach repository guidance

## Purpose and architectural authority

Kavach is a programmable, recoverable smart-account protocol for Cardano. **Safety and correctness come first**, ahead of performance, convenience and feature breadth. Yano Wallet is its initial reference integration; contracts and SDKs must remain wallet-independent.

The protocol is language-independent. JuLC is the initial implementation, not a protocol dependency. Future implementations in Aiken or other Cardano smart-contract languages must follow the same normative wire schema, invariants and conformance fixtures. Do not define protocol encoding solely through Java field order or compiler behavior. Conformance does not imply identical script hashes or addresses.

Read [ADR-001](adr/adr-001-kavach-programmable-smart-account-architecture.md) before architectural or implementation work. It is **Proposed**, not an implemented or audited specification. Preserve its security invariants, distinguish settled requirements from feasibility gates, and record material changes in an ADR. Do not silently promote future capabilities into V1.

For CIP-113 interoperability or changes to extension/authorization boundaries, also read [ADR-002](adr/adr-002-kavach-cip113-interoperability.md). Keep the platform implementation reference, CCL integration candidate and actual deployed contract versions distinct.

## Required stack

- Smart contracts: **JuLC**, targeting **Plutus V3**.
- Build: **Gradle**; commit and use the Gradle Wrapper when scaffolding the project. Do not introduce Maven as a parallel build.
- Java package root and Gradle group: **`com.bloxbean.cardano.kavach`**.
- Baseline: Java 25 toolchain and a compatible pinned Gradle 9.x wrapper, subject to verification with the selected JuLC release.
- Pin compatible JuLC/compiler/VM and off-chain dependencies together. Avoid floating versions; document any necessary snapshot commit.

The repository contains a Phase 0 protocol baseline, executable specification helpers and isolated feasibility contracts under `com.bloxbean.cardano.kavach.phase0`. It also contains a Phase 1 development account implementation whose qualification is tracked in [the Phase 1 acceptance ledger](docs/phase1/completion-checklist.md). The archived Phase 1 state remains sealed. Current sources are implementing Phase 2; consult [its acceptance ledger](docs/phase2/completion-checklist.md) before claiming recovery or deployment readiness. Read the [qualification report](docs/phase0/qualification.md) and [acceptance ledger](docs/phase0/completion-checklist.md) before extending it. The sealed state holder permanently locks disposable devnet deposits; the owned-asset and deregistration fixtures intentionally differ from production rules. Separate probes do not enforce the complete account protocol. Preserve the distinction between JVM specification models, compiled primitive tests and full ledger validation.

The experiment pins CCL `0.8.0-pre5` and unmodified upstream JuLC main with the merged `serialiseData` fix, published locally as: `0.1.0-pre17-6754861-SNAPSHOT`. Read [toolchain provenance and build instructions](toolchain/julc/README.md); the fix is not yet an upstream release. `verifyJulcToolchain` checks the resolved JuLC binary hashes before compilation. `./gradlew check` runs conformance tests, Javadoc syntax/link validation and the positive `toolchainAcceptance` gate; `./gradlew integrationTest` runs live local Yaci DevKit tests and must fail when DevKit is unavailable. Scripts must be tested as emitted by the compiler. The former test-only UPLC rewrite has been removed and MUST NOT be restored as a way to pass acceptance. Phase 0 completion is tracked by its acceptance ledger and does not approve production use. Real positive-reward tests run explicitly as `rewardCreditIntegrationTest` and `pairedRewardCreditIntegrationTest`; they may take 95 minutes. Never delete their `build/phase0` pending manifests or signed devnet CBORs, reset DevKit or change its parameters to accelerate them. The paired test can resume with `-PresumePairedRewards=true`.

## JuLC implementation discipline

Prefer imports and simple class names over fully qualified class names in Java code. For example, add `import java.util.ArrayList;` and use `ArrayList` rather than `java.util.ArrayList` inline. Use fully qualified names only when needed to resolve a naming conflict.

Read the complete [JuLC AI starter pack](https://julc.dev/ai/starter-pack/) before generating contract code; verify APIs against the pinned release. Use typed ledger objects, records and sealed variants; raw `PlutusData` is reserved for opaque module boundaries. Keep validator methods static. Use `BigInteger` for ledger quantities and counters. Follow JuLC's restrictions on mutation, loops, lambdas and parameter types. Keep JVM-only code out of contracts, and account for different byte-array equality behavior on the JVM.

Use the [Gradle setup guide](https://julc.dev/getting-started/) for annotation processing and artifact loading. Separate contract compilation, protocol types/encoding, off-chain transaction building and wallet integration. Suggested modules are described in ADR-001; create them only as needed.

The Phase 1 full positive-reward gate runs explicitly as `phase1RewardCreditIntegrationTest`; it can wait 95 minutes for actual proposal refunds. Never reset DevKit or change its parameters to accelerate it. Its signing keys remain only in the running test process; its public pending manifest is not a resumable signing backup. `phase1RewardReuseIntegrationTest` can create a fresh account using the public original pending setup only when both checkpoint hashes and immutable sinks still match and neither refund has arrived. This preserves pending proposals, not the old account keys. Never run two spend workers against the same reward balances; hand off only after the replacement account is ready.

Fee qualification uses `feeOptimizationIntegrationTest` for four complete transfer profiles and `feeOptimizationRewardIntegrationTest` for real positive rewards. Public evidence and pending manifests go to `build/fee-optimization`, preserving Phase 1 evidence. See [the optimization review](docs/fee-optimization/review.md). Do not reset DevKit or restart an active reward worker merely to collect another fee sample.

Add Javadocs to validators and public Java APIs wherever required. Explain validator purpose, parameter order, security invariants, authorization boundaries and phase-specific limitations; document non-obvious helper checks where maintenance could weaken safety.

Phase 2 SDK/live work is tracked in [its progress report](docs/phase2/progress.md). Run
`phase2TransferIntegrationTest` for current-script transfers and `phase2LifecycleIntegrationTest`
for short state mutations. Evidence goes under `build/phase2`, preserving prior phases.
These short tests do not prove the actual minimum recovery delay or clean-device restoration.

## Security requirements

- Authenticate state using the full state NFT asset identifier, quantity, expected validator and supported datum schema. A matching datum identifier is insufficient.
- Enforce lifecycle, replay and value rules in immutable core code. Bind module execution to the same account, operation, state version and intent digest.
- Authorize upgrades using the old configuration. Spending authority must not implicitly grant administration, unfreeze or recovery authority.
- Treat redeemers, outputs, reference inputs, index hints, signatures, module configuration and relayer input as untrusted.
- Bind signatures to canonical typed intent encoding and deployment domain. Expiry and state version alone do not provide single-use replay protection.
- Account for every native asset; separate recipient allocations, valid account change and bounded lovelace fees. Prevent double satisfaction.
- Use transaction validity intervals for time checks. Never use wall-clock time in contract logic.
- Do not claim freeze front-runs a competing spend, unsupported cryptography works on-chain, or core upgrades preserve addresses automatically.
- Keep private keys, seed phrases, OAuth tokens and production credentials out of source, fixtures and logs.

## Validation and delivery

Follow the [JuLC testing guide](https://julc.dev/guides/testing-guide/). Test compiled UPLC, not just Java helpers. Contract changes need adversarial rejection cases and execution-budget coverage. Maintain encoding/signature golden vectors, and run full transaction validation for ledger-dependent behavior; fabricated contexts do not establish ledger acceptance.

Once scaffolded, use `./gradlew test` for local verification and `./gradlew check` for the configured checks. Use focused module tasks where appropriate. Record what actually ran and any unresolved failures. Do not invent task names or silently substitute another build tool.

Before delivery, check documentation links, schema/version compatibility and affected invariants. Keep unrelated work intact. Describe material limitations plainly. Production deployment and audit claims require supporting evidence.

## Maintaining assistant guidance

`AGENTS.md` is the shared source of repository instructions. `CLAUDE.md` imports it; keep that file small to avoid conflicting copies.

`phase2DelayedRecoveryIntegrationTest` is the explicit Phase 2 real-delay gate. It waits at
least 24 hours against ledger timestamps and is excluded from short DevKit tasks. Keep its
worker alive; `build/phase2/recovery/delayed-recovery.json` contains public progress only,
not the signing keys needed to resume. Never reset DevKit, shorten the immutable delay or
restart an active worker just to rerun checks. Other tests must use separate accounts.

Browser signing is a separate development candidate: read [ADR-005](adr/adr-005-browser-wallet-authentication-and-demo.md),
the [bounded profile](protocol/browser/specification.md), and its [acceptance ledger](docs/browser/completion-checklist.md).
`transactionWitnessIntegrationTest` and `coseIntegrationTest` exercise the two explicit
module modes; `:dashboard-app:backend:dashboardIntegrationTest` exercises the local API on disposable DevKit
accounts. These are synthetic wallet responses with actual ledger validation, not named
wallet/hardware compatibility evidence. The same-repo [dashboard app](dashboard-app/README.md) keeps private
keys in browser wallets. Preserve scheme 0, immutable sources and historical evidence.
Use `dashboardWebInstall` / `dashboardWebBuild` for the pinned frontend. Never reset DevKit to bypass
a recovery delay or a positive reward balance.

## Native companion apps

The iOS Yano Companion lives in `companion-apps/ios`. Run `swift test` from that
folder for Swift core tests; open `YanoCompanion.xcodeproj` for device builds. Keep
its bundle identifier and signing-domain strings stable when reorganizing sources.
`Config/Local.xcconfig`, pairing keys, Xcode user state and build output are local
only and must remain ignored. Simulator builds do not establish physical-device
Keychain or biometric qualification.
