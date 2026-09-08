# Kavach

A proposed programmable, recoverable smart-account protocol for Cardano. JuLC is the initial contract implementation language; the protocol is intended to support independent implementations.

**Status: Phase 0 and Phase 1 transfer development qualification complete; Phase 2 administration and recovery qualification in progress. No production deployment or usable recovery wallet.** The first withdrawal/signature experiment found and isolated a JuLC compilation defect. The pinned source-level fix passes compiler, Kavach and Yaci DevKit tests without artifact rewriting.

Requirements: Java 25 and the [pinned JuLC snapshot published to Maven local](toolchain/julc/README.md). The included Gradle 9.2.0 wrapper resolves other dependencies from Maven Central; CCL is `0.8.0-pre5`.

```sh
./gradlew check                 # compiled conformance, SDK tests, Javadocs and toolchain acceptance
./gradlew integrationTest       # local Yaci DevKit, disposable faucet funds
./gradlew toolchainAcceptance   # positive signature check, no artifact rewriting
```

Integration tests use the local backend at `localhost:8080/api/v1/` and faucet at `localhost:10000`, expecting protocol version 11. They generate fresh keys in memory and never load wallet credentials. They create persistent devnet stake registrations and permanently locked disposable state-probe deposits; they do not reset DevKit. Live tests run explicitly, separately from `check`.

See [ADR-001](adr/adr-001-kavach-programmable-smart-account-architecture.md), [ADR-002](adr/adr-002-kavach-cip113-interoperability.md), the [Phase 0 qualification report](docs/phase0/qualification.md), and the [acceptance ledger](docs/phase0/completion-checklist.md). The [protocol baseline](protocol/v1/specification.md) includes CDDL, module ABI, initialization graph and language-neutral conformance fixtures.

Fee optimization is development-qualified, including actual positive rewards: ordinary transfer fees fell from 1.209 to 1.024 ADA (15.3%). See [the report](docs/fee-optimization/progress.md) and [safety review](docs/fee-optimization/review.md). The original Phase 1 evidence remains preserved separately.

The [Phase 1 acceptance ledger](docs/phase1/completion-checklist.md),
[implementation review](docs/phase1/review.md), and [SDK guide](docs/phase1/sdk.md)
describe the development account implementation. It supports creator-bound initialization,
exact-input partial transfers and large whole-UTxO transfers with raw Ed25519 authorization.
Its state validator is deliberately sealed: Phase 2 administration/recovery will require
new development script hashes. Current sources implement that Phase 2 graph; archived Phase 1 accounts remain sealed. Phase 1 reference outputs and state deposits cannot be reclaimed.

Explicit long tests exercise actual governance-deposit refunds into reward accounts:

```sh
./gradlew rewardCreditIntegrationTest
./gradlew pairedRewardCreditIntegrationTest
./gradlew phase1RewardCreditIntegrationTest  # full account spend with real core/module rewards
# Resume an interrupted paired experiment using its existing local pending files:
./gradlew pairedRewardCreditIntegrationTest -PresumePairedRewards=true
./gradlew phase0BuildManifest    # selected compiler and emitted probe template hashes
./gradlew phase1BuildManifest    # account templates and implementation source hashes
```

Each reward test can take 95 minutes at the recorded epoch settings. Keep both
`build/phase0` and `build/phase1` intact while they run. Do not reset DevKit or run `clean`
during an active reward experiment. The Phase 0 paired test can resume from its matching
pending manifest and prebuilt signed CBORs on the same cluster. The Phase 1 test retains
signing keys only in its live process so it can authorize a short-lived intent after the
refund arrives; its public pending manifest alone cannot resume that process.

`python3 scripts/validate_cddl.py` runs independent structural conformance with Ruby `cddl` 0.12.14 on PATH. Gradle separately checks semantic rules, rendering, signature vectors and compiled rejection behavior. Reports are under `build/reports/tests`; retained public evidence is under `docs/phase0/evidence` and `docs/phase1/evidence`. The protocol remains proposed; production account validators, wallet recovery and independent security review belong to later phases.

Current administration, recovery and locator work is tracked in the
[Phase 2 acceptance ledger](docs/phase2/completion-checklist.md) and
[progress report](docs/phase2/progress.md). Run `phase2LifecycleIntegrationTest` for short
mutations and authority replacement, and `phase2TransferIntegrationTest` for transfer
profiles. `phase2DelayedRecoveryIntegrationTest` is an explicit real 24-hour gate; its
public pending manifest does not back up the worker's signing keys. Keep a running worker
alive and never run `clean` or reset DevKit during qualification.

## Browser wallet demo

The same-repository [Kavach demo](demo/README.md) provides a React/CF Connect interface and
a local Java API for transaction-witness or bounded CIP-8/COSE authentication, transfers
and account management. See its setup guide and [qualification status](docs/browser/completion-checklist.md)
before treating a wallet/version or deployment as supported.
