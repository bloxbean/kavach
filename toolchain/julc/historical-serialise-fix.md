# JuLC compiler fix used by the Phase 0 probe

Upstream `main` was fetched on 2026-09-06 at `37a696b27df0d80b18d88452ba053ca3afb91287`. The `serialiseData` force-count defect was still present. The isolated local fix is commit `1a46882ec3fdebd057fc71c6e720c14dcdfba36c`, published to Maven local as **`0.1.0-pre17-1a46882-SNAPSHOT`**. The fix is tracked in [issue #132](https://github.com/bloxbean/julc/issues/132) and submitted for review in [PR #133](https://github.com/bloxbean/julc/pull/133). It is not yet merged or released.

The [reviewable patch](serialise-data-force.patch) removes `SerialiseData` from `UplcGenerator.forceCount`'s one-force case. This monomorphic builtin takes a Data argument directly; applying `force` to it is invalid. No VM behavior, protocol encoding, signature format or Kavach contract source was changed to accommodate the bug.

The regression test first failed on both Java VM PV10 and PV11 targets on unmodified upstream source. With the source fix, it verifies the generated program returns expected CBOR for zero, a negative integer, empty bytes and an empty constructor. The `julc-compiler:test` task passed **1,485 tests** and `julc-annotation-processor` passed **20 tests**, with no skipped tests. The attempted Scalus explicit-PV11 test was unsupported by that provider and was not counted as validation; live DevKit validation is recorded separately.

## Reproduce the exact local snapshot

The small [Git bundle](serialise-data-force.bundle) preserves the exact fix commit and its changed objects, with upstream `37a696b2` as a prerequisite. The patch is for human review/upstream submission; applying it as a new commit may produce a different snapshot version. Use the bundle to reproduce the pinned commit.

From the Kavach repository root, with Java 25 installed:

```sh
git clone https://github.com/bloxbean/julc.git /tmp/julc-kavach-build
git -C /tmp/julc-kavach-build fetch "$PWD/toolchain/julc/serialise-data-force.bundle" refs/heads/fix/kavach-serialise-data
git -C /tmp/julc-kavach-build checkout --detach 1a46882ec3fdebd057fc71c6e720c14dcdfba36c
cd /tmp/julc-kavach-build
./gradlew :julc-compiler:test :julc-annotation-processor:test -PskipSigning=true
./gradlew :julc-core:publishToMavenLocal \
  :julc-ledger-api:publishToMavenLocal :julc-vm:publishToMavenLocal \
  :julc-compiler:publishToMavenLocal :julc-stdlib:publishToMavenLocal \
  :julc-bls:publishToMavenLocal :julc-blueprint:publishToMavenLocal \
  :julc-annotation-processor:publishToMavenLocal :julc-testkit:publishToMavenLocal \
  :julc-cardano-client-lib:publishToMavenLocal :julc-vm-java:publishToMavenLocal \
  -PskipSigning=true
```

Then return to Kavach and run `./gradlew clean check` and `./gradlew integrationTest`. `check` includes the positive toolchain acceptance gate. Artifacts are compiled normally; the former test-only AST rewrite has been deleted.

Kavach resolves only the pinned JuLC snapshot from Maven local. Other dependencies come from Maven Central; CCL is explicitly aligned to `0.8.0-pre5`, including the annotation-processor classpath. Upstream main still declared older CCL dependencies when inspected; Kavach's override is intentional.

[manifest-1a46882.json](manifest-1a46882.json) records the upstream/fixed commits, patch checksum and SHA-256 of every resolved JuLC binary. `verifyJulcToolchain`, required before Java compilation, rejects mismatched versions, unexpected JuLC modules and changed binaries. Dependency locking alone cannot protect a mutable `SNAPSHOT`. If a rebuild differs, investigate the compiler/build environment and review the differences before updating this manifest; do not regenerate it merely to suppress a failure.

This is a tested experimental local toolchain, not an upstream release or a production approval. Move to a reviewed upstream release when this fix is merged and published, update provenance and artifact hashes, and rerun the full acceptance/conformance/live tests. Never restore post-compilation artifact rewriting to make a test pass.
