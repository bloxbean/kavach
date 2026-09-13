# Aiken size spike: does a different compiler reach a single Conway tier?

Status: measurement spike. **One validator ported, not a second protocol implementation.**
Nothing here is deployed, audited or qualified, and no Java contract, wire schema or toolchain
pin changes. Evidence: [aiken-size-spike.json](evidence/aiken-size-spike.json).

## The question, and the threshold set before running it

[The script size analysis](script-size-analysis.md) established that a browser-module transfer
needs a **29.7%** byte reduction to fit one 25,600-byte Conway tier, and that JuLC-side
representation work recovers at most about 7%. A different compiler was the only remaining
candidate.

The threshold was fixed in advance: the three transfer scripts total 36,408 template bytes and
applied parameters add about 1,328, so they fit one tier only at **1.50x** smaller templates
(1.47x on charged bytes). Below roughly 1.3x the saving would not justify a second implementation.

## Result: idiomatic Aiken is 1.04x smaller. It does not close the gap.

| | JuLC `0.1.0-pre17-6754861` | Aiken `v1.1.23` + stdlib `v2.2.0` |
| --- | ---: | ---: |
| flat bytes | 7,877 | **7,570** |
| CBOR bytes | 7,883 | 7,573 |
| UPLC terms | 8,794 | 7,239 |
| distinct subterms | 2,063 | 3,091 |
| largest repeated subterm | 304 bytes | 144 bytes |

**The number you would actually ship is 1.04x.** Idiomatic Aiken for this validator is 7,570 bytes,
and it validates strictly more than JuLC's 7,877.

That extra validation comes from two places where the Java reinterprets `Data` without checking it
and Aiken, being typed, cannot:

| Java unchecked cast | Aiken equivalent | structural validation Aiken adds |
| --- | --- | ---: |
| `(CoreRedeemer) (Object) redeemer` | typed `redeemer: CoreRedeemer` | 1,470 bytes |
| `(AccountState) (Object) inline.datum()` | `expect state: AccountState = inline` in `resolve_from` | 481 bytes |
| **combined** | | **1,951 bytes** |

Each is measured by a probe validator minus the 98-byte `probe_raw` baseline. Removing both from the
full validator gives **7,877 / 5,619 = 1.40x** — but that over-corrects, since part of that
validation overlaps shape checks the validator performs anyway. So 1.40x is an upper bound on the
like-for-like ratio, and it is still short of the 1.50x a tier needs. Crossing 1.50x would have
required the state `expect` alone to cost at least 849 bytes; it costs 481.

Reaching even the 1.40x bound means writing Aiken against raw `Data` instead of its types, giving up
the structural checks that are Aiken's safety advantage over JuLC's unchecked casts. That is not
a trade worth making for bytes.

## The two compilers differ where the earlier analysis predicted

The term counts explain the near-tie. Aiken emits **18% fewer terms** but only 4% fewer bytes, and
has **50% more distinct subterms** — its output is markedly less repetitive. Its largest repeated
subterm is worth 144 bytes against JuLC's 304.

That is the Z-combinator finding seen from the other side: Aiken already performs the common
subexpression elimination that `UplcOptimizer` lacks. The arithmetic lines up — JuLC's measured
CSE headroom on this validator is 261 bytes, so a JuLC with CSE would land near 7,616 against
Aiken's 7,570, essentially a tie. **Most of JuLC's disadvantage here is the missing CSE pass, and
closing it would still not approach a tier.**

## Wire parity is proven, not assumed

A smaller number obtained by dropping checks would be worthless. Two guarantees:

1. **Schema.** `wire_conformance.ak` builds `conformance/v1/state.hex` from the Aiken types and
   requires `serialise_data` to reproduce it byte-for-byte, and the Blake2b-256 digest to match
   `state-rendering.txt`. Both pass. Had any field or variant order drifted from the Java records,
   the bytes would differ.
2. **Checks.** Every check in the Java call graph — `AccountStateValidator.validate`,
   `StateTransitionLib.{resolve, authenticate, sponsorInputs, coreBinding, outputs}`,
   `LifecycleLib.{stateShape, checkedAdd, proposal, extendCooldown, copy, successor}` and the
   `AccountLib` helpers those reach — has a counterpart in the port, in the same order. All
   seventeen `stateFields` conditions, all nine `Action` indices with `TransferWholeUtxo` at 8,
   and both `successor` branches that must error are present.

This is source-level correspondence plus fixture-level encoding proof. It is **not** the
adversarial rejection suite or execution-budget coverage AGENTS.md requires of an implementation,
and the port has never been executed against a ledger.

## What the spike found besides the ratio

ADR-001 requires the CDDL to be authoritative and forbids Java field order from defining the wire
format — but until now the Java records were both the schema and its only implementation, so that
separation had never been tested. It holds: `kavach.cddl` was sufficient to write the types, and
the fixtures caught the one place it mattered.

The CDDL's `tx-out-ref = #6.121([tx-id: hash32, output-index])` puts the transaction id in as a
bare `hash32`, the flattened Plutus V3 form, which is exactly Aiken's `OutputReference`. Had JuLC's
`TxOutRef(TxId, Int)` wrapped `TxId` as its own constructor, every signed `IntentDomain` would have
been incompatible between implementations. It does not.

## Recommendation

**Do not port the remaining validators for size.** Idiomatic Aiken is 1.04x smaller, and even an
un-idiomatic Aiken written against raw `Data` is bounded at 1.40x, short of the 1.50x a tier needs; and a full port buys a permanently doubled security surface plus its own implementation
review. The 0.048 ADA from the upstream CSE fix remains the better-value item, and this spike
independently corroborates that CSE is where JuLC's gap actually is.

The one caveat: this is a single validator, and the transfer-path scripts are list- and
crypto-heavier, so their ratio may differ. The margin is wide enough that a different ratio
elsewhere is unlikely to reach 1.50x, but this measurement does not prove it.

An Aiken implementation may still be worth doing for reasons that are not size — toolchain risk
(the protocol currently pins an unreleased snapshot of a pre-release compiler), or independent
implementation diversity. Those are separate decisions with their own ADR, and this spike says
nothing about them beyond showing the conformance fixtures are good enough to support one.

## Pinned versions

The comparison is only reproducible with the recorded pins. `aiken.toml` fixes the compiler at
`v1.1.23` and the stdlib at `v2.2.0`; `aiken.lock` records the resolved stdlib. Stdlib v3.x was
also available locally but was not used: generated code differs across stdlib majors, and v2.2.0
is the pairing established for the v1.1 compiler line. A different pair is a different
measurement. `./gradlew aikenSizeSpike` refuses any other compiler version.

## Reproduce

```sh
cd contracts-aiken && aiken check && aiken build   # trace level defaults to silent
```

`aiken build --trace-level compact` yields 7,944 bytes and `verbose` 10,502; the comparison uses
the default silent output, matching JuLC's traceless emission.
