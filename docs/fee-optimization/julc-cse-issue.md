# Upstream issue draft: hoist the Z combinator (bloxbean/julc)

Draft for filing against JuLC. Kept in-tree so the measurement and the report stay together.
Measured against `0.1.0-pre17-6754861-SNAPSHOT` (upstream `main` at
`6754861bc1e803d3f91b5e7fdfd1b7eccbec200a`).

---

**Title:** `UplcGenerator.generateLetRec` emits the Z combinator inline at every recursive binding

**Summary**

Every recursive binding emits its own copy of the Z fixpoint combinator

```
\f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
```

which costs 19 flat bytes each. `UplcOptimizer` runs force-delay-cancel, constant-fold,
exp-mod-literal, dead-code-elimination, beta-reduce, eta-reduce and constr-case-reduce, none of
which hoists it — there is no common subexpression elimination pass. The combinator is already a
named constant in `UplcOptimizer` (`Z_COMBINATOR`, used for purity analysis), so the compiler
recognises it; it just never shares it.

**Impact (measured on a real contract set)**

| script | flat bytes | Z occurrences | recovered by hoisting |
| --- | ---: | ---: | ---: |
| AccountStateValidator | 7,877 | 17 | 261 |
| CoreCheckpoint | 10,478 | 44 | 707 |
| BrowserModule | 15,377 | 52 | 837 |
| StateNftPolicy | 5,695 | 22 | 344 |
| AccountAssetValidator | 10,553 | 35 | 557 |
| **total** | **49,980** | **170** | **2,706 (5.4%)** |

On Cardano this is charged twice: as a per-transaction Conway reference-script fee over 25,600-byte
tiers, and as the min-UTxO locked by each reference publication. For one deployment that is roughly
0.048 ADA per transaction and 11.4 ADA less locked per account.

**Proposed fix**

Hoist the combinator to a single binding wrapping the program:
`(\Z -> body[every occurrence := Z]) Zcombinator`.

**Why it is sound**

The combinator is a **closed** term — no free De Bruijn index escapes it — so the rewrite is
exactly a beta-expansion of a closed value. It cannot capture a variable, and because the argument
is a `Lam` (already a value) it duplicates no work and reorders no error under strict evaluation.

Verified mechanically rather than argued: substituting the combinator back into the hoisted body
reproduces the original term **byte-for-byte** on all five scripts above. Re-encoding the restored
program yields bytes identical to the original.

Execution cost should be neutral to slightly lower — each site becomes a `Var` lookup instead of
constructing a `Lam`, against one extra `Apply` at startup — but that needs budget measurement,
not assumption.

**Generalising**

A real CSE pass over closed repeated subterms recovers **at least 3,510 bytes (7.0%)** on the same
set; 3,510 comes from one greedy 40-candidate selection, so treat it as a floor rather than an
estimate of what a thorough pass would reach. A narrower fix worth
having independently: `constantFold` does not fold `constrData n (mkNilData ())`, a
compile-time-known `Data` value, which one of these scripts builds 147 times across two tags.

**Not reproduced here**

`julc.optimization=pv11-costed` with the `cardano-node-11.0.1-plutus-v3-pv11` cost profile produces
byte-identical output to the default `pv11-safe` on this set, so the extra cost-justified rules do
not currently affect it.
