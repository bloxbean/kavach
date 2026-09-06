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

The repository currently contains architecture documentation only. Module names and commands below are implementation targets, not existing facilities. Do not report builds or tests as passing until they exist and have run.

## JuLC implementation discipline

Prefer imports and simple class names over fully qualified class names in Java code. For example, add `import java.util.ArrayList;` and use `ArrayList` rather than `java.util.ArrayList` inline. Use fully qualified names only when needed to resolve a naming conflict.

Read the complete [JuLC AI starter pack](https://julc.dev/ai/starter-pack/) before generating contract code; verify APIs against the pinned release. Use typed ledger objects, records and sealed variants; raw `PlutusData` is reserved for opaque module boundaries. Keep validator methods static. Use `BigInteger` for ledger quantities and counters. Follow JuLC's restrictions on mutation, loops, lambdas and parameter types. Keep JVM-only code out of contracts, and account for different byte-array equality behavior on the JVM.

Use the [Gradle setup guide](https://julc.dev/getting-started/) for annotation processing and artifact loading. Separate contract compilation, protocol types/encoding, off-chain transaction building and wallet integration. Suggested modules are described in ADR-001; create them only as needed.

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
