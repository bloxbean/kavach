# Pinned upstream JuLC toolchain

Kavach now uses unmodified upstream `main` at
`6754861bc1e803d3f91b5e7fdfd1b7eccbec200a`, the merge of
[serialization-fix PR #133](https://github.com/bloxbean/julc/pull/133).
It is published to Maven local as **`0.1.0-pre17-6754861-SNAPSHOT`**.
CCL remains explicitly aligned to **`0.8.0-pre5`** across all classpaths.

The fresh upstream build passed 1,485 compiler tests and 20 annotation-processor tests,
with zero failures or skips. The compiler source tree matches the previously used fixed
checkout. No additional patch or emitted-artifact rewrite is applied. This is a pinned
upstream snapshot, not a release or production approval.

[manifest.json](manifest.json) pins the exact upstream commit, local version and every
resolved JuLC binary SHA-256. `verifyJulcToolchain` rejects changed binary bytes, unexpected
modules and version drift before compilation. Dependency locking alone does not protect
a mutable snapshot. Investigate and review differences before updating the manifest.

## Reproduce

With Java 25 installed, use a fresh checkout:

```sh
git clone https://github.com/bloxbean/julc.git /tmp/julc-kavach-upstream
git -C /tmp/julc-kavach-upstream checkout --detach 6754861bc1e803d3f91b5e7fdfd1b7eccbec200a
cd /tmp/julc-kavach-upstream
./gradlew :julc-compiler:test :julc-annotation-processor:test -PskipSigning=true
./gradlew :julc-core:publishToMavenLocal \
  :julc-ledger-api:publishToMavenLocal :julc-vm:publishToMavenLocal \
  :julc-compiler:publishToMavenLocal :julc-stdlib:publishToMavenLocal \
  :julc-bls:publishToMavenLocal :julc-blueprint:publishToMavenLocal \
  :julc-annotation-processor:publishToMavenLocal :julc-testkit:publishToMavenLocal \
  :julc-cardano-client-lib:publishToMavenLocal :julc-vm-java:publishToMavenLocal \
  -PskipSigning=true
```

Return to Kavach and run `./gradlew check`, followed by the phase-specific live gates.
Do not clean or delete pending long-running test artifacts. The current Phase 2 acceptance
status is tracked in [its ledger](../../docs/phase2/completion-checklist.md); passing a
compiler test does not establish complete account or recovery correctness.

## Historical Phase 0/1 provenance

The previous snapshot `0.1.0-pre17-1a46882-SNAPSHOT`, its
[artifact manifest](manifest-1a46882.json), [build report](historical-serialise-fix.md),
[patch](serialise-data-force.patch) and [Git bundle](serialise-data-force.bundle) remain
available to reproduce archived Phase 0, Phase 1 and fee-optimization evidence.
The old report describes the state before the upstream merge; it does not describe the
current dependency. Do not relabel historical results with the new toolchain version.
