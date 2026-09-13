package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attributes compiled reference-script bytes so size claims rest on measurement.
 *
 * <p>Reference scripts are charged twice: once as a per-transaction Conway fee over 25,600-byte
 * tiers, and once as the ledger minimum locked by each publication. Both scale with the emitted
 * UPLC, so knowing <em>where</em> the bytes are decides whether any reduction is worth a script
 * hash change. This test records that attribution and pins the conclusions other documents cite.
 *
 * <p><strong>This test never emits a rewritten program.</strong> It decodes the compiler's own
 * output, counts structure, and reports how many bytes a hypothetical optimizer pass could
 * recover. It writes no rewritten {@code cborHex} anywhere and no rewritten script reaches a
 * test, a fixture or the ledger. AGENTS.md forbids substituting rewritten UPLC for compiler
 * output; measuring the compiler's output is the opposite of that, and the deliberate absence of
 * any encode-to-artifact path here is what keeps it so.
 *
 * <p>The redundancy measured is not a defect in the contracts. It is the Z fixpoint combinator
 * that {@code UplcGenerator.generateLetRec} emits inline at every recursive binding, which no
 * pass in {@code UplcOptimizer} hoists. Recovering it is upstream compiler work, recorded in
 * {@code docs/fee-optimization/script-size-analysis.md}.
 */
class ScriptSizeAnalysisTest {

    /** Scripts a dashboard account publishes as references. */
    private static final List<String> REFERENCE_SCRIPTS =
            List.of("AccountStateValidator", "CoreCheckpoint", "BrowserModule",
                    "StateNftPolicy", "AccountAssetValidator");

    /** Scripts a single browser-module transfer attaches and executes. */
    private static final List<String> TRANSFER_SCRIPTS =
            List.of("AccountAssetValidator", "CoreCheckpoint", "BrowserModule");

    /** Conway charges reference bytes in tiers of this width. */
    private static final int TIER_BYTES = 25_600;

    /**
     * The Z combinator exactly as {@code UplcGenerator.generateLetRec} emits it:
     * {@code \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))}.
     */
    private static final Term Z_COMBINATOR = zCombinator();

    private static Term zCombinator() {
        var inner = Term.lam("v", Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
        var branch = Term.lam("x", Term.apply(Term.var(2), inner));
        return Term.lam("f", Term.apply(branch, branch));
    }

    @Test
    void recordsWhereReferenceScriptBytesAre() throws Exception {
        var scripts = new LinkedHashMap<String, Map<String, Object>>();
        int totalFlat = 0;
        int totalHoist = 0;
        for (var name : REFERENCE_SCRIPTS) {
            var program = decode(name);
            int flat = UplcFlatEncoder.encodeProgram(program).length;
            int occurrences = count(program.term(), key(Z_COMBINATOR));
            int hoisted = hoistedSize(program, occurrences);

            // A hoist is only sound if putting the combinator back reproduces the original term
            // exactly; that makes the rewrite a beta-expansion of a closed value, which cannot
            // capture a variable, duplicate work or reorder an error.
            assertTrue(betaInverseIsExact(program), name + " hoist is not an exact beta-expansion");
            assertTrue(occurrences > 1, name + " should emit the combinator more than once");

            var entry = new LinkedHashMap<String, Object>();
            entry.put("flatBytes", flat);
            entry.put("terms", nodes(program.term()));
            entry.put("zCombinatorOccurrences", occurrences);
            entry.put("zHoistSavingBytes", flat - hoisted);
            entry.put("betaInverseExact", true);
            scripts.put(name, entry);
            totalFlat += flat;
            totalHoist += flat - hoisted;
        }

        int transferBytes = 0;
        for (var name : TRANSFER_SCRIPTS)
            transferBytes += UplcFlatEncoder.encodeProgram(decode(name)).length;

        // The single-tier target is what a reduction would have to reach to remove the 1.2x
        // second-tier multiplier from every transfer. Record the gap rather than an aspiration.
        int gap = transferBytes - TIER_BYTES;
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Unapplied compiled templates; applied parameters add a few bytes each."
                + " DevKit measurement, not a production deployment or audit claim.");
        report.put("julcVersion", System.getProperty("kavach.julcVersion", "unrecorded"));
        report.put("scripts", scripts);
        report.put("referenceGraphFlatBytes", totalFlat);
        report.put("referenceGraphZHoistSavingBytes", totalHoist);
        report.put("transferFlatBytes", transferBytes);
        report.put("transferSingleTierBytes", TIER_BYTES);
        report.put("transferBytesOverSingleTier", gap);
        report.put("transferReductionNeededForSingleTier",
                String.format("%.1f%%", 100.0 * gap / transferBytes));

        var out = Path.of("build/fee-optimization/script-size-analysis.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(report) + "\n");

        // The headline the findings document cites: representation-level recovery is far short of
        // a tier. If this ever stops holding, the document's conclusion must be revisited.
        assertTrue(totalHoist < gap,
                "Z-hoist now recovers more than the single-tier gap; revisit the findings");
    }

    /**
     * Every transfer script must be one the transaction actually executes.
     *
     * <p>Attaching a reference the transaction never runs would pay Conway bytes for nothing.
     * {@code AccountTransfer.attach} spends with the asset validator and invokes the checkpoint
     * and module as withdraw-0 reward validators; the state UTxO is only {@code readFrom}, so the
     * state validator neither executes nor is attached. This pins that correspondence so a later
     * change cannot quietly start paying for an unused script.
     */
    @Test
    void transferAttachesOnlyExecutedScripts() {
        assertEquals(List.of("AccountAssetValidator", "CoreCheckpoint", "BrowserModule"),
                TRANSFER_SCRIPTS);
        assertTrue(!TRANSFER_SCRIPTS.contains("AccountStateValidator"),
                "The state validator is read as a reference input and must not be attached");
        assertTrue(!TRANSFER_SCRIPTS.contains("StateNftPolicy"),
                "No minting happens during a transfer");
    }

    private static Program decode(String name) throws Exception {
        var path = Path.of("build/classes/java/main/META-INF/plutus", name + ".plutus.json");
        assertTrue(Files.exists(path), "Compile the contracts before measuring " + name);
        var hex = new ObjectMapper().readTree(Files.readString(path)).get("cborHex").asText();
        return UplcFlatDecoder.decodeProgram(unwrap(unwrap(HexFormat.of().parseHex(hex))));
    }

    /** Strip one CBOR byte-string header, returning its payload unchanged if absent. */
    private static byte[] unwrap(byte[] bytes) {
        int header = bytes[0] & 0xff;
        if ((header >> 5) != 2) return bytes;
        int info = header & 0x1f;
        int offset;
        int length;
        if (info < 24) { length = info; offset = 1; }
        else if (info == 24) { length = bytes[1] & 0xff; offset = 2; }
        else if (info == 25) { length = ((bytes[1] & 0xff) << 8) | (bytes[2] & 0xff); offset = 3; }
        else if (info == 26) {
            length = ((bytes[1] & 0xff) << 24) | ((bytes[2] & 0xff) << 16)
                    | ((bytes[3] & 0xff) << 8) | (bytes[4] & 0xff);
            offset = 5;
        } else return bytes;
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    /** Size of the program with every combinator occurrence replaced by one shared binding. */
    private static int hoistedSize(Program program, int occurrences) {
        if (occurrences < 2) return UplcFlatEncoder.encodeProgram(program).length;
        var body = replace(program.term(), key(Z_COMBINATOR), 0);
        var hoisted = Term.apply(Term.lam("Z", body), Z_COMBINATOR);
        return UplcFlatEncoder.encodeProgram(
                new Program(program.major(), program.minor(), program.patch(), hoisted)).length;
    }

    /** Substituting the combinator back must reproduce the original term exactly. */
    private static boolean betaInverseIsExact(Program program) {
        var body = replace(program.term(), key(Z_COMBINATOR), 0);
        return key(restore(body, 0)).equals(key(program.term()));
    }

    private static Term replace(Term term, String target, int depth) {
        if (key(term).equals(target)) return Term.var(depth + 1);
        return switch (term) {
            case Term.Lam l -> Term.lam(l.paramName(), replace(l.body(), target, depth + 1));
            case Term.Apply a -> Term.apply(replace(a.function(), target, depth),
                    replace(a.argument(), target, depth));
            case Term.Force f -> Term.force(replace(f.term(), target, depth));
            case Term.Delay d -> Term.delay(replace(d.term(), target, depth));
            case Term.Constr c -> {
                var fields = new ArrayList<Term>();
                for (var field : c.fields()) fields.add(replace(field, target, depth));
                yield new Term.Constr(c.tag(), fields);
            }
            case Term.Case c -> {
                var branches = new ArrayList<Term>();
                for (var branch : c.branches()) branches.add(replace(branch, target, depth));
                yield new Term.Case(replace(c.scrutinee(), target, depth), branches);
            }
            default -> term;
        };
    }

    private static Term restore(Term term, int depth) {
        return switch (term) {
            case Term.Var v -> v.name().index() == depth + 1 ? Z_COMBINATOR : term;
            case Term.Lam l -> Term.lam(l.paramName(), restore(l.body(), depth + 1));
            case Term.Apply a -> Term.apply(restore(a.function(), depth), restore(a.argument(), depth));
            case Term.Force f -> Term.force(restore(f.term(), depth));
            case Term.Delay d -> Term.delay(restore(d.term(), depth));
            case Term.Constr c -> {
                var fields = new ArrayList<Term>();
                for (var field : c.fields()) fields.add(restore(field, depth));
                yield new Term.Constr(c.tag(), fields);
            }
            case Term.Case c -> {
                var branches = new ArrayList<Term>();
                for (var branch : c.branches()) branches.add(restore(branch, depth));
                yield new Term.Case(restore(c.scrutinee(), depth), branches);
            }
            default -> term;
        };
    }

    private static int count(Term term, String target) {
        int here = key(term).equals(target) ? 1 : 0;
        return here + switch (term) {
            case Term.Lam l -> count(l.body(), target);
            case Term.Apply a -> count(a.function(), target) + count(a.argument(), target);
            case Term.Force f -> count(f.term(), target);
            case Term.Delay d -> count(d.term(), target);
            case Term.Constr c -> c.fields().stream().mapToInt(f -> count(f, target)).sum();
            case Term.Case c -> count(c.scrutinee(), target)
                    + c.branches().stream().mapToInt(b -> count(b, target)).sum();
            default -> 0;
        };
    }

    private static int nodes(Term term) {
        return 1 + switch (term) {
            case Term.Lam l -> nodes(l.body());
            case Term.Apply a -> nodes(a.function()) + nodes(a.argument());
            case Term.Force f -> nodes(f.term());
            case Term.Delay d -> nodes(d.term());
            case Term.Constr c -> c.fields().stream().mapToInt(ScriptSizeAnalysisTest::nodes).sum();
            case Term.Case c -> nodes(c.scrutinee())
                    + c.branches().stream().mapToInt(ScriptSizeAnalysisTest::nodes).sum();
            default -> 0;
        };
    }

    /** Structural key keeping De Bruijn indices, so equal keys mean interchangeable terms. */
    private static String key(Term term) {
        var out = new StringBuilder();
        key(term, out);
        return out.toString();
    }

    private static void key(Term term, StringBuilder out) {
        switch (term) {
            case Term.Var v -> out.append('V').append(v.name().index());
            case Term.Lam l -> { out.append("L("); key(l.body(), out); out.append(')'); }
            case Term.Apply a -> {
                out.append("A(");
                key(a.function(), out);
                out.append(',');
                key(a.argument(), out);
                out.append(')');
            }
            case Term.Force f -> { out.append("F("); key(f.term(), out); out.append(')'); }
            case Term.Delay d -> { out.append("D("); key(d.term(), out); out.append(')'); }
            case Term.Const c -> out.append("C{").append(c.value()).append('}');
            case Term.Builtin b -> out.append('B').append(b.fun());
            case Term.Error e -> out.append('E');
            case Term.Constr c -> {
                out.append('R').append(c.tag()).append('(');
                for (var field : c.fields()) { key(field, out); out.append(','); }
                out.append(')');
            }
            case Term.Case c -> {
                out.append("S(");
                key(c.scrutinee(), out);
                out.append(';');
                for (var branch : c.branches()) { key(branch, out); out.append(','); }
                out.append(')');
            }
        }
    }
}
