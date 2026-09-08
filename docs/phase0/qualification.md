# Phase 0 qualification report — 2026-09-07

Phase 0 qualifies the ordinary-account specification and its implementation primitives. It does not implement a usable Kavach wallet, authorize production funds, or establish that the final combined validators fit their budgets. The [acceptance ledger](completion-checklist.md) and [completion record](evidence/completion-2026-09-07.json) establish closure. Both [real positive-reward experiments](positive-rewards.md) passed after actual proposal-deposit refunds. Final counts are 217 local tests/diagnostics, one positive compiler acceptance gate and ten live DevKit tests, with no failures or skips.

The protocol baseline is [CDDL](../../protocol/v1/kavach.cddl), [semantic rules](../../protocol/v1/specification.md), [module ABI](../../protocol/v1/module-abi.md), and [initialization graph](../../protocol/v1/deployment.md). Its version is `v1.0-rc1`. Production template hashes are not frozen. A later implementation must enforce the complete baseline and repeat integrated adversarial, budget and ledger tests. A language-independent wire contract does not imply identical JuLC/Aiken addresses.

## Specification and conformance

Nine ordinary actions have canonical CBOR, digest, rendering, public-key and signature fixtures. Three additional edge vectors cover native-token quantity/name boundaries, a staked recipient, consolidation and whole transfer with a uint64 token quantity. Wire/signature vectors use synthetic account identities and test signing keys: they establish encoding and primitive signatures, not policy authorization for a deployed account. The full twelve-field state has Normal and RecoveryPending fixtures; the latter retains the complete target configuration, commitment and execution deadline. Genesis, configuration replacement and recovery target possession use separate proof domains. Tests compare CCL decoding/re-encoding, the strict Java decoder/renderer and compiled `serialiseData`/hash/signature evaluation. The signer can bind and render resolved state, including recovery terms; callers still must authenticate its NFT/address and ledger reference.

The independent Ruby `cddl` validator version 0.12.14 checks 21 structural fixtures and rejects a non-envelope root. This complements semantic tests; CDDL alone does not enforce ordering, canonical Cardano serialization, role independence or arithmetic. Run `python3 scripts/validate_cddl.py` with that pinned validator on PATH. It does not install dependencies or use network access. `generateWireVectors` writes review candidates only under `build`; it never silently replaces golden fixtures. Newly generated signatures use fresh disposable keys, so they are not a reproducibility artifact.

The first module profile bounds registry/role/proof sizes, rejects duplicate public keys and validates public points off-chain. Compiled threshold and all-key possession primitives reject malformed evidence and incorrect signatures. The live signature probe rejects an identity-public-key forgery. Full configuration validation in the production module remains Phase 1/2 work; these crypto primitives are not a module implementation.

The profile preserves creation-time delay/cooldown exactly because no V1 action signs updates to them; unsigned increases reject too. The recovery oracle tests role/mode guards, delayed completion, target substitution, cancellation to Frozen, monotonic cooldown, sequence/version bounds and 10,000 deterministic transition attempts. The stored target configuration fixes an ambiguity in completion validation: a commitment alone cannot reconstruct the old initiation envelope after state mutation. Actual state transitions, competing spends and clean-device locator recovery remain Phase 2.

CIP-113 selects ADR-002 outcome B: ordinary V1 defers programmable-token interoperability, withdraws the hash-preservation promise and accepts possible future migration. This closes the architectural fork; it is not a passed CIP-113 prototype.

## Live ledger evidence

The local target is cardano-node 11.0.1, revision `97036a66bcf8c89f687ae57a048eecc0389977ef`, Conway protocol version 11, network magic 42. Tests use fresh in-memory keys and disposable faucet ADA, without resetting DevKit or modifying protocol parameters. The recorded genesis has system start `2026-09-06T14:26:40Z`, one-second slots and 600-slot epochs.

The eight fast tests cover:

- Raw signature verification, invalid signatures/identity keys, consumed-input replay, legacy registration and zero withdrawal.
- Creator-bound one-shot NFT initialization, exact genesis identity and authenticated reference lookup; fake state and repeated mint rejection.
- Explicit registration purpose dispatch, missing witness, duplicate registration and deregistration rejection.
- Separate core/module withdrawals with the same authenticated account/action and exact Rewarding redeemer binding; wrong signatures and mismatched evidence reject.
- Controlled authorized deregistration followed by permissionless legacy re-registration; first registration plus withdrawal in one transaction rejects.
- Two identical always-fails-locked reference copies and full-witness fallback, all using the same script hash.
- A whole-UTxO transfer of 140 native tokens with external ADA top-up; removing a token rejects at the node. Its asset holder is an independently authorized disposable fixture, not a production Kavach validator.
- POSIX validity conversion, exclusive upper-bound containment and a one-millisecond-too-early deadline rejection. Omitting `SlotConfig` fails the local time-sensitive evaluation as expected.

See the [original signature](withdrawal-probe.md), [state identity](state-identity-probe.md), [lifecycle](reward-lifecycle-probe.md) and [checkpoint composition](checkpoint-composition.md) reports for their historical runs. Current public transaction IDs and failure responses are retained under [evidence](evidence/).

The two long tests create information proposals whose deposits refund into script reward accounts after expiry. They use actual ledger credit, not fabricated contexts. `rewardCreditIntegrationTest` checks that zero and partial withdrawals reject once credited and full withdrawal succeeds. `pairedRewardCreditIntegrationTest` checks two positive withdrawals to a shared immutable sink with distinct receipt outputs, rejecting receipt reuse. Both use prebuilt, signed, no-expiry transactions retained only under `build/phase0`; no private keys are persisted. These tests can take 95 minutes on this cluster.

An earlier single-reward attempt established a real credit and zero-withdrawal rejection, then failed construction of its partial branch because the sponsor lacked the gross output funding CCL selected before accounting for rewards. It is recorded as a failed attempt, not a successful full withdrawal. Subsequent runs fund the sponsor adequately and preflight all branches before waiting. The paired test initially inherited a 240-second harness timeout; its 6,000-second timeout and explicit `-PresumePairedRewards=true` path allow completion from the original signed branches without new proposals. Resume requires the matching local pending manifest/CBORs and an unchanged cluster; do not run `clean` while either experiment is pending.

## Budget qualification and design corrections

The recorded ledger permits 10,000,000,000 CPU steps, 16,500,000 memory units, 16,384 transaction bytes and a 5,000-byte ledger Value. Its minimum-ADA coefficient is 4,310 lovelace per byte. [Budget evidence](evidence/) records consumed units, not only generous test ceilings.

| Component / stress shape | CPU | Memory |
| --- | ---: | ---: |
| Core binding, 16 inputs / 4 references / 16 outputs | 466,821,520 | 1,495,240 |
| Module binding, same context | 533,483,356 | 1,491,956 |
| Eight-signature threshold, 8,192-byte opaque payload | 1,216,685,575 | 2,015,633 |
| Sixteen-key possession, 1,536-byte payload | 2,567,350,389 | 6,254,187 |
| Conservation, dense 12-asset value | 2,142,712,411 | 7,676,569 |
| Conservation, 128 varied maximum-entry distributions | 2,708,533,188 | 9,877,144 |
| Whole transfer, 8,168-byte Plutus Value / 189 native assets | 1,053,842,846 | 1,359,309 |

The distribution search is deterministic and bounded, not an exhaustive proof of maximum cost. Summing component measurements does not measure final production execution, state validation or configuration decoding. Phase 1/2 must qualify complete operations at the supported limits; reduce the profile or optimize before production if the combined implementation exceeds them. Never relax a security check to meet a budget.

Repeated generic Value merges and nested asset lookups exhausted the memory limit at the earlier 32-asset shape. The conservation experiment now flattens once and merges ordered delta lists, with 12 assets including ADA and at most 12 native entries across account inputs and transaction outputs. These limits cannot become a trap for larger unsolicited deposits: action 8 signs one exact input and its full Value digest, preserves every native token and at least its ADA, uses a key recipient, prohibits account-funded fees and requires all other outputs to be plain ADA-only key outputs. Sponsors supply fees/top-ups. Its 8,192-byte Plutus Value bound is stress-tested beyond the current ledger Value limit. It adds no generic dApp or CIP-113 escape path.

| Serialization upper shape | Bytes | Largest output minimum ADA (lovelace) |
| --- | ---: | ---: |
| Spend | 9,698 | 1,314,550 |
| Whole UTxO | 13,369 | 22,368,900 |
| Start recovery | 14,072 | 14,597,970 |
| Replace module | 15,438 | 7,977,810 |
| Complete recovery | 13,104 | 7,977,810 |
| Creation | 11,374 | 7,977,810 |

These are deliberately oversized serialization models with 16 inputs/outputs/key witnesses/required signers, four references, three collateral inputs and per-operation state/evidence shapes. They are not ledger-valid transactions or quotes for actual fees. Reference scripts are assumed; normal full-witness fallback is conditional on size. Creation includes the singleton mint field and an overlarge mint-redeemer stand-in; its actual template and invocation still require Phase 1 qualification. Exact output minimum ADA must be recalculated from the final output and current parameters. Normal/Frozen state is capped at 1,536 Data bytes; pending state at 3,072 because it retains both configurations.

## Review findings and remaining implementation obligations

The source-level `serialiseData` fix is pinned and verified by JAR hashes; [JuLC issue #132](https://github.com/bloxbean/julc/issues/132) and [PR #133](https://github.com/bloxbean/julc/pull/133) preserve upstream review. No post-compilation AST repair is used. Typed record projections need explicit constructor/arity checks. Contract code avoids the unsupported nested Value projection and unsupported compiler constructs found during these experiments.

Local provisional evaluation is not final ledger validation. `ClientBridgeDiagnosticsTest` reproduces two defects in the pinned upstream converter: `CclTxConverter.getSortedWithdrawalCredentials` sorts Bech32 reward strings rather than ledger address bytes, and `CclValueConverter.fromAmounts` can reverse native token ordering through map insertion. The diagnostic asserts these known discrepancies so an upstream upgrade forces their review; it is not a conformance pass for the bridge. See [the recorded counterexamples](evidence/client-bridge-diagnostics-2026-09-07.json). ADA is explicitly moved first by Value serialization; the reproduced Value defect concerns native token order.

The withdrawal ordering can assign a script's measured budget to the other rewarding index. This explains why a paired transaction passed backend evaluation but exhausted its node budget. Its probe now gives both indices the same stress-tested ceiling and still requires local preflight, final backend evaluation and node confirmation. The whole-value probe uses a fixed bounded allocation and final backend/node validation because a native-map digest depends on the actual ledger ordering. These are explicit experimental workarounds. Production builders must fix/requalify the converter, use correct byte/purpose ordering, include bounded headroom and validate the final balanced transaction. No source-level bridge fix or production-ready SDK is claimed here.

Registration deposits are not pinned as immutable mutable-network parameters in the production ABI. Legacy registration is witness-free on this target; explicit deposit registration has a Certifying witness. Neither permits a registration invocation to authorize an account operation. References are discovered by script hash and backed by retained bytes. A frozen or recovering account still requires an external collateral provider.

The final implementation must enforce NFT identity, immutable core binding, lifecycle, exact input replay, role/configuration validation, all value and receipt disjointness, and full transaction shape together. Tests of separate primitives cannot certify that composition. Independent security review and production release qualification remain mandatory later phases.


## Reproducibility

`./gradlew check phase0BuildManifest` records the selected BellSoft Liberica Java 25.0.2 compiler, Gradle 9.2.0, CCL 0.8.0-pre5, JuLC snapshot and emitted template hashes. A separate clean directory reproduced all 14 probe template CBORs and compiler metadata exactly. The independent build also runs the full unit/conformance and positive toolchain gates. `verifyProtocolBaseline` checks the reviewed schema/specification/threat-model/fixture manifest, including its complete file set; a deliberate schema edit in the disposable rebuild was rejected. `verifyJulcToolchain` separately checks the pinned compiler JAR bytes.

The baseline manifest detects drift; it is not an audit signature. Review and version schema/fixture changes together rather than regenerating hashes to hide failures. Keep source and emitted artifact provenance separate from randomly parameterized live probe deployment hashes. The original and independent build results are retained with the final completion evidence.
