package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test-only switch that substitutes the Aiken size-spike port for a JuLC validator.
 *
 * <p>Selected with {@code -Dkavach.aikenContracts=all} or a comma-separated list of JuLC class simple names.
 * The Aiken blueprint's compiled code receives the same parameters, in the same order, as the JuLC
 * validator, so every compiled-script test runs unchanged against either implementation. This is a
 * comparison harness, not a deployment path: Aiken scripts have different hashes and are not qualified.
 */
final class AikenScripts {
    private static final Map<String, String> VALIDATORS = Map.ofEntries(
            Map.entry("AccountStateValidator", "account_state.account_state"),
            Map.entry("CoreCheckpoint", "core_checkpoint.core_checkpoint"),
            Map.entry("AccountAssetValidator", "account_asset.account_asset"),
            Map.entry("BudgetAccountAssetValidator", "budget_account_asset.budget_account_asset"),
            Map.entry("StateNftPolicy", "state_nft.state_nft"),
            Map.entry("PeriodicBudgetValidator", "periodic_budget.periodic_budget"),
            Map.entry("PeriodicBudgetNftPolicy", "periodic_budget_nft.periodic_budget_nft"),
            Map.entry("Ed25519Module", "ed25519_module.ed25519_module"),
            Map.entry("BrowserModule", "browser_module.browser_module"),
            Map.entry("PolicyModule", "policy_module.policy_module"),
            Map.entry("BudgetPolicyModule", "budget_policy_module.budget_policy_module"),
            Map.entry("MixedSetupModule", "mixed_setup_module.mixed_setup_module"));
    private static final Set<String> SELECTED = Arrays.stream(System.getProperty("kavach.aikenContracts", "").split(","))
            .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
    private static JsonNode blueprint;

    private AikenScripts() {
    }

    /**
     * Whether any validator is substituted; evidence writers must then avoid qualification directories.
     */
    static boolean active() {
        return !SELECTED.isEmpty();
    }

    static boolean selected(Class<?> type) {
        return SELECTED.contains(type.getSimpleName()) || (SELECTED.contains("all") && VALIDATORS.containsKey(type.getSimpleName()));
    }

    static PlutusV3Script load(Class<?> type, PlutusData... args) {
        var prefix = VALIDATORS.get(type.getSimpleName()) + ".";
        String code = null;
        for (var validator : blueprint().get("validators"))
            if (validator.get("title").asText().startsWith(prefix)) {
                code = validator.get("compiledCode").asText();
                break;
            }
        if (code == null) throw new IllegalStateException("Aiken blueprint has no validator " + prefix);
        // Blueprint code is CBOR-wrapped flat; ledger script bytes wrap it once more.
        var wrapped = HexFormat.of().formatHex(cborBytes(HexFormat.of().parseHex(code)));
        return JulcScriptAdapter.fromProgram(JulcScriptAdapter.toProgram(wrapped).applyParams(args));
    }

    /**
     * Appends one evaluation outcome to {@code kavach.budgetLog}, when set, so JuLC and Aiken runs
     * of the same suite can be compared per test and role. Records budgets only, never contexts.
     */
    static synchronized void record(String role, EvalResult result) {
        var log = System.getProperty("kavach.budgetLog", "");
        if (log.isEmpty()) return;
        var caller = StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> frame.getClassName().endsWith("Test"))
                .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
                .reduce((first, last) -> last).orElse("unknown"));
        var consumed = result.budgetConsumed();
        var line = caller + "\t" + role + "\t" + (result.isSuccess() ? "success" : "failure")
                + "\t" + consumed.cpuSteps() + "\t" + consumed.memoryUnits() + "\n";
        try {
            Files.writeString(Path.of(log), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Appends one applied script's serialized size to {@code kavach.budgetLog}, when set. The size is
     * the ledger's reference-script measure: the script bytes inside the outer CBOR wrapper.
     */
    static synchronized void recordSize(Class<?> type, PlutusV3Script script) {
        var log = System.getProperty("kavach.budgetLog", "");
        if (log.isEmpty()) return;
        var wrapped = HexFormat.of().parseHex(script.getCborHex());
        int header = (wrapped[0] & 31) < 24 ? 1 : (wrapped[0] & 31) == 24 ? 2 : (wrapped[0] & 31) == 25 ? 3 : 5;
        try {
            Files.writeString(Path.of(log), "size\t" + type.getSimpleName() + "\t" + (wrapped.length - header) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] cborBytes(byte[] value) {
        int length = value.length;
        byte[] header = length < 24 ? new byte[]{(byte) (0x40 | length)}
                : length < 256 ? new byte[]{0x58, (byte) length}
                : length < 65536 ? new byte[]{0x59, (byte) (length >> 8), (byte) length}
                : new byte[]{0x5a, (byte) (length >> 24), (byte) (length >> 16), (byte) (length >> 8), (byte) length};
        var result = Arrays.copyOf(header, header.length + length);
        System.arraycopy(value, 0, result, header.length, length);
        return result;
    }

    private static synchronized JsonNode blueprint() {
        if (blueprint == null) {
            var path = Path.of(System.getProperty("kavach.aikenBlueprint", "contracts-aiken/plutus.json"));
            try {
                blueprint = new ObjectMapper().readTree(Files.readString(path));
            } catch (IOException e) {
                throw new UncheckedIOException("Build the Aiken port first (aiken build in contracts-aiken)", e);
            }
        }
        return blueprint;
    }
}
