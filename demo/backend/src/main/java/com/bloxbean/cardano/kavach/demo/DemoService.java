package com.bloxbean.cardano.kavach.demo;

import co.nstant.in.cbor.CborDecoder;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.coinselection.impl.ExcludeUtxoSelectionStrategy;
import com.bloxbean.cardano.client.coinselection.impl.LargestFirstUtxoSelectionStrategy;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.DuplicateScriptWitnessChecker;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.StakingCredential;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.auth.browser.BrowserModule;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.*;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import com.bloxbean.cardano.kavach.protocol.PolicyConfigCodec;
import com.bloxbean.cardano.kavach.auth.policy.PolicyModule;
import com.bloxbean.cardano.kavach.auth.policy.MixedSetupModule;
import com.bloxbean.cardano.kavach.auth.policy.BudgetPolicyModule;
import com.bloxbean.cardano.kavach.protocol.PeriodicBudgetCodec;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Configuration;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Budget;
import com.bloxbean.cardano.kavach.sdk.PeriodicBudgetDeployment;
import com.bloxbean.cardano.kavach.sdk.PeriodicBudgetTransfer;
import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Local DevKit orchestration. Public plans contain no keys and signatures never authorize a changed
 * body.
 */
final class DemoService {
    private static final Network NETWORK = new Network(0, 42);
    private static final Path PROFILES = Path.of("demo/backend/data/profiles");
    private final BackendService backend =
            new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final CompanionExchange companion = new CompanionExchange(Path.of(
            System.getProperty("kavach.companion.identity", "demo/backend/data/companion-request-key.bin")));

    Object companionPairing() throws Exception { return companion.pairing(); }

    @FunctionalInterface
    private interface Build {
        Transaction build() throws Exception;
    }

    @FunctionalInterface
    private interface Next {
        Plan next() throws Exception;
    }

    private static final class Request {
        final int id;
        final String purpose;
        final byte[] key;
        final byte[] payload;
        byte[] evidence;
        int signingMethod;
        final UUID companionTicket = UUID.randomUUID();
        String companionProfile;
        PlutusData companionData;
        PlutusData companionState;
        long companionExpiresAt;

        Request(int id, String purpose, byte[] key, byte[] payload) {
            this.id = id;
            this.purpose = purpose;
            this.key = key;
            this.payload = payload;
        }
    }

    private static final class Plan {
        final String id = UUID.randomUUID().toString();
        final String title;
        String review;
        final List<Request> requests = new ArrayList<>();
        final Set<String> required = ConcurrentHashMap.newKeySet();
        String feePayerAddress;
        String feePayerKeyHash;
        final Set<String> transactionAuthoritySigners = new TreeSet<>();
        Transaction transaction;
        Build build;
        Next next;
        Plan advanced;
        int setupStep;
        int setupTotal;
        String txHash;
        String locator;
        final Map<String, VkeyWitness> witnesses = new LinkedHashMap<>();

        Plan(String title, String review) {
            this.title = title;
            this.review = review;
        }
    }

    private static final class Setup {
        AccountDeployment.Scripts scripts;
        AccountState state;
        Utxo seed;
        String sponsor;
        String holder;
        int mode;
        PlutusV3Script finalModule;
    }

    /**
     * Network ID zero is shared by testnets; verify the configured ledger's actual genesis magic.
     */
    private void requireDevkit() throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:10000/local-cluster/api/admin/devnet/genesis/shelley"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(65537);
                require(
                        response.statusCode() == 200 && bytes.length <= 65536,
                        "DevKit genesis metadata unavailable");
                var genesis = new ObjectMapper().readTree(bytes);
                require(
                        genesis.path("networkMagic").asInt(-1) == 42
                                && genesis.path("slotLength").asInt(-1) == 1,
                        "The configured DevKit must use magic 42 and one-second slots");
                var latest = backend.getBlockService().getLatestBlock();
                require(latest.isSuccessful(), "Ledger tip unavailable");
                long start = Instant.parse(genesis.path("systemStart").asText()).getEpochSecond();
                require(
                        latest.getValue().getTime() == start + latest.getValue().getSlot(),
                        "Indexer and DevKit genesis disagree");
            }
        }
    }

    Object status() throws Exception {
        requireDevkit();
        var p = backend.getEpochService().getProtocolParameters();
        return Map.of(
                "online",
                p.isSuccessful(),
                "network",
                "Yaci DevKit · magic 42",
                "signing",
                List.of("CIP-30 transaction", "Bounded CIP-8 / COSE", "Mixed amount-tiered approval", "Optional daily/weekly budget"));
    }

    Object restore(String locator) throws Exception {
        var restored = AccountLocator.parse(locator).restore(AccountLocator.provider(backend));
        var state = restored.state();
        int mode = profile(state.authModule().scriptHash());
        String address =
                AddressProvider.getEntAddress(
                                com.bloxbean.cardano.client.address.Credential.fromScript(
                                        state.coreBinding().assetValidator()),
                                NETWORK)
                        .toBech32();
        var amounts = new LinkedHashMap<String, BigInteger>();
        for (var input : utxos(address))
            for (var value : input.getAmount())
                amounts.merge(value.getUnit(), value.getQuantity(), BigInteger::add);
        var value = new LinkedHashMap<String, Object>();
        value.put("locator", locator);
        value.put("address", address);
        value.put("accountId", hex(state.accountId().policy()));
        value.put("mode", state.mode().getClass().getSimpleName());
        value.put("version", state.stateVersion().toString());
        value.put("signingMode", mode);
        value.put("setupPending", Files.exists(PROFILES.resolve(hex(state.authModule().scriptHash()) + ".setup")));
        value.put("budgetCore", PeriodicBudgetDeployment.supports(state));
        if (PeriodicBudgetCodec.isProfile(state.authConfig())) value.put("budget", budgetView(state));
        value.put("balance", amounts.getOrDefault("lovelace", BigInteger.ZERO).toString());
        value.put(
                "assets",
                amounts.entrySet().stream()
                        .filter(e -> !e.getKey().equals("lovelace"))
                        .map(e -> Map.of("unit", e.getKey(), "quantity", e.getValue().toString()))
                        .toList());
        var config = config(state.authConfig());
        if (mode >= 3) {
            var policy = PolicyConfigCodec.decode(PeriodicBudgetCodec.authorization(state.authConfig()));
            value.put("smallPaymentLimit", policy.smallPaymentLimit().toString());
            value.put("smallSpend", Map.of("threshold", policy.smallSpend().threshold(), "members", PolicyConfigCodec.ids(policy.smallSpend())));
        }
        value.put(
                "keys",
                keys(config).stream()
                        .map(
                                k ->
                                        Map.of(
                                                "id",
                                                k.credentialId().intValueExact(),
                                                "publicKey",
                                                hex(k.publicKey()),
                                                "method", PolicyConfigCodec.method(state.authConfig(), k.credentialId(), mode)))
                        .toList());
        String[] names = {"Spend", "Admin", "Freeze", "Unfreeze", "Recovery", "Cancel"};
        var policies =
                List.of(
                        config.spend(),
                        config.admin(),
                        config.freeze(),
                        config.unfreeze(),
                        config.recovery(),
                        config.cancel());
        var roles = new ArrayList<Object>();
        for (int i = 0; i < 6; i++) {
            var policy = policies.get(i);
            roles.add(
                    Map.of(
                            "role",
                            names[i],
                            "threshold",
                            policy.threshold(),
                            "members",
                            javaList(policy.credentialIds())));
        }
        value.put("policies", roles);
        if (state.mode() instanceof RecoveryPending pending)
            value.put(
                    "recovery",
                    Map.of(
                            "targetKeys",
                            keys(config(pending.targetConfig())).stream()
                                    .map(
                                            k ->
                                                    Map.of(
                                                            "id",
                                                            k.credentialId().intValueExact(),
                                                            "publicKey",
                                                            hex(k.publicKey())))
                                    .toList(),
                            "executeAfter",
                            pending.executeAfter().toString(),
                            "sequence",
                            state.recoverySequence().toString()));
        return value;
    }

    /** Verifies an explicitly domain-separated, non-authorizing public-key export from a wallet. */
    Object enrollment(Map<String, Object> body) throws Exception {
        var payload =
                MessageDigest.getInstance("SHA-256")
                        .digest(
                                "Kavach demo public key export v1"
                                        .getBytes(StandardCharsets.UTF_8));
        var key = BrowserSignatures.publicKey(DemoServer.string(body, "key"));
        BrowserSignatures.fromCip30(
                key,
                payload,
                DemoServer.string(body, "signature"),
                DemoServer.string(body, "key"),
                0);
        return Map.of("publicKey", hex(key), "paymentKeyHash", hex(BrowserSignatures.keyHash(key)));
    }

    Object plan(String id) throws Exception {
        return view(get(id));
    }

    Object prepare(Map<String, Object> body) throws Exception {
        requireDevkit();
        String action = DemoServer.string(body, "action");
        String sponsor = address(DemoServer.string(body, "sponsor"));
        if (action.equals("Create account")) {
            var config = parseKeys(DemoServer.string(body, "keys"), body.get("policies"));
            int mode = mode(body);
            require(mode <= 3, "Create with per-key signing, then enable the optional budget from Security");
            var configuration = mode == 3 ? policyConfiguration(body, config, mode, null) : AccountCodec.data(config);
            // Genesis needs every transaction-method key plus the fee payer. Reject before
            // publishing references if that union exceeds the existing signer bound.
            var genesisSigners = new HashSet<String>();
            genesisSigners.add(hex(payment(sponsor)));
            for (var key : keys(config)) {
                if (PolicyConfigCodec.method(configuration, key.credentialId(), mode) == 1)
                    genesisSigners.add(hex(BrowserSignatures.keyHash(key.publicKey())));
            }
            require(genesisSigners.size() <= 16,
                    "Creation supports at most 16 transaction signers including the fee wallet. Use COSE for a key or fund setup with a registered transaction signer.");
            var coins =
                    utxos(sponsor).stream()
                            .filter(DemoService::plain)
                            .sorted(Comparator.comparing(DemoService::lovelace))
                            .toList();
            require(
                    coins.size() >= 2,
                    "Creation needs two separate plain ADA outputs: a reserved seed and"
                            + " fee/collateral funding. Fund this DevKit wallet twice.");
            var setup = new Setup();
            setup.seed = coins.getFirst();
            setup.sponsor = sponsor;
            setup.mode = mode;
            var discriminator = new byte[32];
            new SecureRandom().nextBytes(discriminator);
            var domain =
                    new DeploymentDomain(BigInteger.ZERO, BigInteger.valueOf(42), discriminator);
            var sink = ledgerAddress(sponsor);
            var scripts =
                    AccountDeployment.derive(domain, ref(setup.seed), payment(sponsor), sink, sink);
            if (Boolean.TRUE.equals(body.get("budgetCore"))) scripts = PeriodicBudgetDeployment.derive(scripts, domain);
            var module = browser(scripts, domain, sink, mode);
            if (mode == 3) {
                setup.finalModule = module;
                module = mixedSetup(scripts, domain, sink, module);
                Files.writeString(PROFILES.resolve(hex(module.getScriptHash()) + ".setup"), setup.sponsor);
            }
            setup.scripts = withModule(scripts, module);
            setup.state =
                    AccountDeployment.genesis(
                            setup.scripts,
                            domain,
                            configuration,
                            BigInteger.valueOf(86400000),
                            BigInteger.valueOf(3600000));
            setup.holder = AddressProvider.getEntAddress(scripts.state(), NETWORK).toBech32();
            return view(publishSetup(setup, 0));
        }
        String locator = DemoServer.string(body, "locator");
        var restored = AccountLocator.parse(locator).restore(AccountLocator.provider(backend));
        var state = restored.state();
        var scripts = AccountDeployment.restore(state, backend);
        int mode = profile(state.authModule().scriptHash());
        if (action.equals("Finish account setup")) return view(finishMixedSetup(locator, sponsor));
        require(!Files.exists(PROFILES.resolve(hex(state.authModule().scriptHash()) + ".setup")),
                "Finish account setup before changing policies or spending");
        require(
                state.deploymentDomain().networkMagic().equals(BigInteger.valueOf(42)),
                "Locator is not for this DevKit deployment");
        if (action.equals("Send assets"))
            return view(transfer(body, sponsor, state, restored.input(), scripts, mode, locator));
        int destinationMode = action.equals("Replace module") ? mode(body) : mode;
        if (destinationMode == 4 && List.of("Replace module", "Rotate keys", "Start recovery").contains(action)) {
            require(PeriodicBudgetDeployment.supports(state), "Periodic budgets require a new budget-capable account address");
            var previousBudget = PeriodicBudgetCodec.isProfile(state.authConfig()) ? PeriodicBudgetCodec.decode(state.authConfig()).budget() : Optional.<Budget>empty();
            if (Boolean.TRUE.equals(body.get("budgetEnabled")) && previousBudget.isEmpty() && !body.containsKey("budgetCounter"))
                return view(createBudgetCounter(body, sponsor, state));
        }
        Action mutation =
                switch (action) {
                    case "Freeze account" -> new Freeze();
                    case "Unfreeze account" -> new Unfreeze();
                    case "Rotate keys" ->
                            new ReplaceConfig(
                                    policyConfiguration(body, parseKeys(DemoServer.string(body, "target"), body.get("policies")), mode, state));
                    case "Start recovery" ->
                            new StartRecovery(
                                    state.recoverySequence().add(BigInteger.ONE),
                                    policyConfiguration(body, parseKeys(DemoServer.string(body, "target"), body.get("policies")), mode, state));
                    case "Cancel recovery" -> {
                        require(
                                state.mode() instanceof RecoveryPending,
                                "Account has no pending recovery");
                        yield new CancelRecovery(
                                state.recoverySequence(),
                                ((RecoveryPending) state.mode()).proposalCommitment());
                    }
                    case "Complete recovery" -> {
                        require(
                                state.mode() instanceof RecoveryPending,
                                "Account has no pending recovery");
                        yield new CompleteRecovery(
                                state.recoverySequence(),
                                ((RecoveryPending) state.mode()).targetConfig());
                    }
                    case "Replace module" -> {
                        int nextMode = mode(body);
                        require(nextMode != mode, "Choose a different signing method");
                        var candidate =
                                browser(
                                        scripts,
                                        state.deploymentDomain(),
                                        ledgerAddress(sponsor),
                                        nextMode);
                        yield new ReplaceModule(
                                new AuthModuleRef(candidate.getScriptHash(), BigInteger.ONE),
                                policyConfiguration(body, config(state.authConfig()), nextMode, state));
                    }
                    default -> throw new IllegalArgumentException("Unsupported account action");
                };
        if (mutation instanceof ReplaceModule replacement) {
            int nextMode = mode(body);
            var candidate =
                    browser(scripts, state.deploymentDomain(), ledgerAddress(sponsor), nextMode);
            var publication =
                    plainPlan(
                            "Publish candidate module",
                            sponsor,
                            state.coreBinding().stateValidator(),
                            candidate,
                            null);
            publication.next =
                    () -> {
                        var registration = registration(sponsor, List.of(candidate), null);
                        registration.next =
                                () ->
                                        mutation(
                                                action,
                                                sponsor,
                                                state,
                                                restored.input(),
                                                scripts,
                                                mode,
                                                locator,
                                                replacement,
                                                candidate,
                                                nextMode,
                                                body.get("approvers"));
                        return registration;
                    };
            return view(publication);
        }
        return view(
                mutation(
                        action,
                        sponsor,
                        state,
                        restored.input(),
                        scripts,
                        mode,
                        locator,
                        mutation,
                        null,
                        mode,
                        body.get("approvers")));
    }

    /** Creates an unused one-shot counter; installing it still requires the old account admin. */
    private Plan createBudgetCounter(Map<String, Object> body, String sponsor, AccountState state) throws Exception {
        var seed = feeInput(sponsor, null, BigInteger.valueOf(6_000_000));
        var policy = PeriodicBudgetDeployment.mintPolicy(state, ref(seed), payment(sponsor));
        var validator = PeriodicBudgetDeployment.validator(state);
        String unit = hex(policy.getScriptHash());
        String address = AddressProvider.getEntAddress(validator, NETWORK).toBech32();
        var p = new Plan("Create periodic budget counter", "Create a 3 ADA counter deposit for this account. It remains locked under the development contract. The budget is enabled only after a separate admin-approved configuration change.");
        feePayer(p, sponsor);
        p.build = () -> {
            var block = backend.getBlockService().getLatestBlock().getValue();
            var tx = new Tx().collectFrom(List.of(seed))
                    .mintAsset(policy, new com.bloxbean.cardano.client.transaction.spec.Asset("", BigInteger.ONE), BigIntPlutusData.of(0))
                    .payToContract(address, List.of(Amount.ada(3), Amount.asset(unit, "", 1)),
                            PlutusDataAdapter.toClientLib(AccountCodec.data(PeriodicBudgetLib.initial())));
            return build(tx, p, sponsor, address, List.of(), block.getSlot(), block.getSlot() + 300);
        };
        var resumed = new LinkedHashMap<>(body);
        resumed.put("budgetCounter", unit);
        p.next = () -> {
            Files.createDirectories(PROFILES);
            Files.writeString(PROFILES.resolve(unit + ".counter"), hex(state.accountId().policy()));
            return get((String)((Map<?, ?>)prepare(resumed)).get("id"));
        };
        return finish(p);
    }

    private Utxo budgetCounter(AccountState state, Budget budget) throws Exception {
        String address = AddressProvider.getEntAddress(PeriodicBudgetDeployment.validator(state), NETWORK).toBech32();
        String unit = hex(budget.counter().policy()) + hex(budget.counter().name());
        var candidates = utxos(address).stream().filter(u -> u.getAmount().stream()
                .anyMatch(a -> a.getUnit().equals(unit) && a.getQuantity().equals(BigInteger.ONE))).toList();
        require(candidates.size() == 1, "Current budget counter unavailable or ambiguous; refresh confirmed chain state");
        return candidates.getFirst();
    }

    private Object budgetView(AccountState state) throws Exception {
        var configured = PeriodicBudgetCodec.decode(state.authConfig()).budget();
        if (configured.isEmpty()) return Map.of("enabled", false);
        var budget = configured.get();
        var input = budgetCounter(state, budget);
        var usage = PeriodicBudgetCodec.usage(PlutusDataAdapter.fromClientLib(
                com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexUtil.decodeHexString(input.getInlineDatum()))));
        var block = backend.getBlockService().getLatestBlock().getValue();
        var start = PeriodicBudgetCodec.window(BigInteger.valueOf(block.getTime()).multiply(BigInteger.valueOf(1000)), budget.period());
        var spent = usage.period().equals(budget.period()) && usage.windowStart().equals(start) ? usage.spent() : BigInteger.ZERO;
        return Map.of("enabled", budget.limit().signum() > 0, "period", budget.period().equals(BigInteger.TWO) ? "weekly" : "daily",
                "limit", budget.limit().toString(), "spent", spent.toString(), "remaining", budget.limit().subtract(spent).max(BigInteger.ZERO).toString(),
                "resetsAt", start.add(PeriodicBudgetCodec.duration(budget.period())).toString(), "counter", hex(budget.counter().policy()));
    }

    private Plan publishSetup(Setup setup, int index) throws Exception {
        var scripts =
                List.of(
                        setup.scripts.state(),
                        setup.scripts.checkpoint(),
                        setup.scripts.module(),
                        setup.scripts.nft(),
                        setup.scripts.asset());
        if (index < 5) {
            var p =
                    plainPlan(
                            "Create account · publish script " + (index + 1) + " of 5",
                            setup.sponsor,
                            setup.state.coreBinding().stateValidator(),
                            scripts.get(index),
                            setup.seed);
            p.locator = AccountLocator.fromState(setup.state).backup();
            if (index == 0)
                p.review +=
                        "\n"
                            + (setup.mode == 3 ? "Full development setup: six 80 ADA references (480 ADA permanently" : "Full development setup: five 80 ADA references (400 ADA permanently")
                            + " locked), 12 ADA account state, registration deposits and"
                            + " transaction fees. Use disposable DevKit assets only.";
            p.next = () -> publishSetup(setup, index + 1);
            return setupProgress(p, index + 1, setup.mode == 3 ? 10 : 7);
        }
        if (index == 5) {
            var p =
                    registration(
                            setup.sponsor,
                            List.of(setup.scripts.checkpoint(), setup.scripts.module()),
                            setup.seed);
            p.next = () -> publishSetup(setup, 6);
            return setupProgress(p, 6, setup.mode == 3 ? 10 : 7);
        }
        var p =
                new Plan(
                        "Create account · approve genesis",
                        WireFormat.renderState(AccountCodec.data(setup.state)));
        p.locator = AccountLocator.fromState(setup.state).backup();
        var digest = WireFormat.digest(ProofDomains.genesis(AccountCodec.data(setup.state)));
        requests(
                p,
                setup.state.authConfig(),
                allIds(config(setup.state.authConfig())),
                digest,
                "genesis",
                setup.mode);
        if (setup.mode >= 2) for (var request : p.requests) {
            if (request.evidence != null) continue;
            request.companionProfile = "kavach-cose-genesis-v1";
            request.companionData = AccountCodec.data(setup.state);
        }
        feePayer(p, setup.sponsor);
        p.build =
                () -> {
                    var genesis =
                            new GenesisModuleRedeemer(
                                    BigInteger.ONE,
                                    setup.state,
                                    evidence(p, "genesis"),
                                    JulcList.empty());
                    var tx =
                            new Tx()
                                    .collectFrom(
                                            List.of(
                                                    setup.seed,
                                                    feeInput(
                                                            setup.sponsor,
                                                            setup.seed,
                                                            BigInteger.valueOf(15000000))))
                                    .mintAsset(
                                            setup.scripts.nft(),
                                            new com.bloxbean.cardano.client.transaction.spec.Asset(
                                                    "", BigInteger.ONE),
                                            BigIntPlutusData.of(0))
                                    .payToContract(
                                            setup.holder,
                                            List.of(
                                                    Amount.ada(12),
                                                    Amount.asset(
                                                            hex(setup.state.accountId().policy()),
                                                            "",
                                                            1)),
                                            PlutusDataAdapter.toClientLib(
                                                    AccountCodec.data(setup.state)))
                                    .attachRewardValidator(setup.scripts.module())
                                    .withdraw(
                                            reward(setup.scripts.module()),
                                            BigInteger.ZERO,
                                            PlutusDataAdapter.toClientLib(
                                                    AccountCodec.data(genesis)));
                    return build(
                            tx,
                            p,
                            setup.sponsor,
                            setup.holder,
                            List.of(setup.scripts.nft(), setup.scripts.module()),
                            0,
                            0);
                };
        if (setup.mode == 3) {
            p.review += "\nMixed account setup is not complete at genesis. Spending stays blocked until the selected per-key policy is activated. Finish setup uses old-admin approval plus every key's destination possession, with unchanged keys, methods and rules.";
            p.next = () -> finishMixedSetup(p.locator, setup.sponsor);
        }
        return setupProgress(finish(p), 7, setup.mode == 3 ? 10 : 7);
    }

    private static Plan setupProgress(Plan plan, int step, int total) {
        plan.setupStep = step; plan.setupTotal = total; return plan;
    }

    /** Resumes confirmed mixed genesis using public verified deployment records, not pending-plan memory. */
    private Plan finishMixedSetup(String locator, String sponsor) throws Exception {
        var restored = AccountLocator.parse(locator).restore(AccountLocator.provider(backend));
        var state = restored.state();
        var scripts = AccountDeployment.restore(state, backend);
        var record = PROFILES.resolve(hex(state.authModule().scriptHash()) + ".setup");
        require(Files.exists(record), "This account has no unfinished mixed setup");
        var sink = ledgerAddress(address(Files.readString(record)));
        var candidate = browser(scripts, state.deploymentDomain(), sink, 3);
        var expected = mixedSetup(scripts, state.deploymentDomain(), sink, candidate);
        require(Arrays.equals(expected.getScriptHash(), state.authModule().scriptHash()), "Setup deployment record does not match its immutable checkpoint");
        String holder = AddressProvider.getEntAddress(scripts.state(), NETWORK).toBech32();
        if (utxos(holder).stream().noneMatch(u -> hexUnchecked(candidate).equals(u.getReferenceScriptHash()))) {
            var publication = plainPlan("Create account · publish final signing module", sponsor, state.coreBinding().stateValidator(), candidate, null);
            publication.locator = locator;
            publication.next = () -> finishMixedSetup(locator, sponsor);
            return setupProgress(publication, 8, 10);
        }
        if (!setupModuleRegistered(candidate)) {
            var plan = registration(sponsor, List.of(candidate), null);
            // Plan titles are immutable; the purpose is explicit in its review and locator.
            plan.review += "\nFinish mixed account setup: register its final signing module.";
            plan.locator = locator;
            plan.next = () -> finishMixedSetup(locator, sponsor);
            return setupProgress(plan, 9, 10);
        }
        return setupProgress(mutation("Create account · activate selected signing methods", sponsor, state, restored.input(),
                scripts, 3, locator, new ReplaceModule(new AuthModuleRef(candidate.getScriptHash(), BigInteger.ONE),
                        state.authConfig()), candidate, 3, null), 10, 10);
    }

    /**
     * Yaci Store omits Blockfrost's active flag. Query its confirmed stake certificates instead.
     * The precommitted PolicyModule rejects deregistration, so a confirmed registration persists.
     * Bound the scan and fail closed if this development index exceeds the supported history.
     */
    private boolean setupModuleRegistered(PlutusV3Script candidate) throws Exception {
        String expected = reward(candidate);
        var client = HttpClient.newHttpClient();
        for (int page = 1; page <= 100; page++) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:8080/api/v1/stake/registrations?count=100&page=" + page))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() == 200, "Cannot read confirmed stake registrations; retry when DevKit is available");
            var entries = new ObjectMapper().readTree(response.body());
            require(entries.isArray() && entries.size() <= 100, "Invalid stake-registration response");
            for (var entry : entries) if (expected.equals(entry.path("address").asText())
                    && "SCRIPTHASH".equals(entry.path("credential_type").asText())
                    && hex(candidate.getScriptHash()).equals(entry.path("credential").asText())) return true;
            if (entries.size() < 100) return false;
        }
        throw new IllegalArgumentException("Stake-registration history exceeds this demo's bounded resume scan");
    }

    private static PlutusV3Script mixedSetup(AccountDeployment.Scripts scripts, DeploymentDomain domain,
            Address sink, PlutusV3Script finalModule) throws Exception {
        var args = List.of(AccountCodec.data(BigInteger.ONE), AccountCodec.data(BigInteger.ONE), AccountCodec.data(domain),
                PlutusData.bytes(scripts.state().getScriptHash()), PlutusData.bytes(scripts.checkpoint().getScriptHash()),
                AccountCodec.data(sink), PlutusData.bytes(finalModule.getScriptHash()));
        var result = JulcScriptLoader.load(MixedSetupModule.class, args.stream().map(PlutusDataAdapter::toClientLib)
                .toArray(com.bloxbean.cardano.client.plutus.spec.PlutusData[]::new));
        Files.createDirectories(PROFILES);
        Files.writeString(PROFILES.resolve(hex(result.getScriptHash()) + ".mode"), "3");
        return result;
    }

    private Plan plainPlan(
            String title, String sponsor, byte[] stateHash, PlutusV3Script script, Utxo reserved)
            throws Exception {
        var p =
                new Plan(
                        title,
                        "Publish immutable reference script.\nScript: "
                                + hex(script.getScriptHash())
                                + "\n"
                                + "Reference deposit: 80 ADA\n"
                                + "Network: DevKit 42\n"
                                + "This development reference deposit cannot be reclaimed.");
        feePayer(p, sponsor);
        var tx =
                new Tx()
                        .collectFrom(
                                List.of(feeInput(sponsor, reserved, BigInteger.valueOf(82000000))))
                        .payToAddress(
                                AddressProvider.getEntAddress(
                                                com.bloxbean.cardano.client.address.Credential
                                                        .fromScript(stateHash),
                                                NETWORK)
                                        .toBech32(),
                                Amount.ada(80),
                                script);
        p.transaction = setupTransaction(tx, sponsor, reserved);
        return finish(p);
    }

    private Plan registration(String sponsor, List<PlutusV3Script> scripts, Utxo reserved)
            throws Exception {
        var p =
                new Plan(
                        "Register authorization checkpoints",
                        "Register the required script reward accounts on DevKit.\n"
                                + "Ledger stake-registration deposits are included in this"
                                + " transaction.");
        feePayer(p, sponsor);
        var tx =
                new Tx()
                        .collectFrom(
                                List.of(feeInput(sponsor, reserved, BigInteger.valueOf(6000000))));
        for (var script : scripts) tx.registerStakeAddress(reward(script));
        p.transaction = setupTransaction(tx, sponsor, reserved);
        return finish(p);
    }

    /**
     * Keeps the one-shot identity seed out of every setup fee selection, and checks the final body.
     */
    private Transaction setupTransaction(Tx tx, String sponsor, Utxo reserved) throws Exception {
        var excluded =
                reserved == null
                        ? Set.<TransactionInput>of()
                        : Set.of(
                                new TransactionInput(
                                        reserved.getTxHash(), reserved.getOutputIndex()));
        var selection =
                new ExcludeUtxoSelectionStrategy(
                        new LargestFirstUtxoSelectionStrategy(builder.getUtxoSupplier()), excluded);
        var transaction =
                builder.compose(tx.from(sponsor))
                        .feePayer(sponsor)
                        .additionalSignersCount(1)
                        .withUtxoSelectionStrategy(selection)
                        .build();
        require(
                transaction.getBody().getInputs().stream().noneMatch(excluded::contains),
                "Setup attempted to consume the reserved account identity seed");
        return transaction;
    }

    private Plan mutation(
            String title,
            String sponsor,
            AccountState old,
            Utxo stateInput,
            AccountDeployment.Scripts scripts,
            int mode,
            String locator,
            Action action,
            PlutusV3Script candidate,
            int candidateMode,
            Object requestedApprovers)
            throws Exception {
        var block = backend.getBlockService().getLatestBlock().getValue();
        long lower = block.getSlot(), upper = lower + 300;
        var window =
                new AccountMutation.Window(
                        BigInteger.valueOf(block.getTime() * 1000),
                        BigInteger.valueOf((block.getTime() + 300) * 1000),
                        false);
        var intent = intent(old, stateInput, action, window);
        var next =
                AccountAdministration.successor(old, intent, window.lower(), window.upper(), false);
        var p =
                new Plan(
                        title,
                        WireFormat.renderSigningRequest(
                                AccountCodec.data(intent),
                                AccountCodec.data(old),
                                ref(stateInput).toPlutusData(),
                                null));
        p.locator = locator;
        var config = config(old.authConfig());
        boolean completion = action instanceof CompleteRecovery;
        if (!completion) {
            var policy =
                    switch (action) {
                        case ReplaceConfig x -> config.admin();
                        case ReplaceModule x -> config.admin();
                        case Freeze x -> config.freeze();
                        case Unfreeze x -> config.unfreeze();
                        case StartRecovery x -> config.recovery();
                        case CancelRecovery x -> config.cancel();
                        default -> throw new IllegalArgumentException("Unsupported action");
                    };
            requests(
                    p,
                    old.authConfig(),
                    selectApprovers(policy, requestedApprovers),
                    AccountCodec.intentDigest(intent, null),
                    "operation",
                    mode);
        }
        if (action instanceof ReplaceConfig replacement) {
            var target = config(replacement.newConfig());
            requests(
                    p,
                    replacement.newConfig(),
                    mode >= 3 ? allIds(target) : introduced(config, target),
                    WireFormat.digest(
                            ProofDomains.configuration(
                                    AccountCodec.data(old), AccountCodec.data(intent))),
                    "possession",
                    mode);
        }
        if (action instanceof ReplaceModule replacement)
            requests(
                    p,
                    replacement.newConfig(),
                    allIds(config(replacement.newConfig())),
                    WireFormat.digest(
                            ProofDomains.configuration(
                                    AccountCodec.data(old), AccountCodec.data(intent))),
                    "candidate",
                    candidateMode);
        if (action instanceof CompleteRecovery replacement) {
            var target = config(replacement.replacementConfig());
            var ids = new TreeSet<>(introduced(config, target));
            ids.addAll(selectApprovers(target.spend(), null));
            requests(
                    p,
                    replacement.replacementConfig(),
                    mode >= 3 ? allIds(target) : List.copyOf(ids),
                    WireFormat.digest(
                            ProofDomains.target(AccountCodec.data(old), AccountCodec.data(intent))),
                    "possession",
                    mode);
        }
        if (action instanceof ReplaceConfig || action instanceof ReplaceModule)
            for (var request : p.requests) {
                if (request.evidence != null) continue;
                request.companionProfile = "kavach-cose-policy-v1";
                request.companionData = AccountCodec.data(intent);
                request.companionState = AccountCodec.data(old);
            }
        feePayer(p, sponsor);
        p.build =
                () -> {
                    var current =
                            new ModuleRedeemer(
                                    BigInteger.ONE,
                                    intent,
                                    completion
                                            ? Optional.empty()
                                            : Optional.of(
                                                    new Proof(
                                                            BigInteger.valueOf(mode),
                                                            evidence(p, "operation"))),
                                    evidence(p, "possession"),
                                    JulcList.empty());
                    var proposed =
                            candidate == null
                                    ? Optional.<ModuleRedeemer>empty()
                                    : Optional.of(
                                            new ModuleRedeemer(
                                                    BigInteger.ONE,
                                                    intent,
                                                    Optional.empty(),
                                                    evidence(p, "candidate"),
                                                    JulcList.empty()));
                    var auth = new BrowserAuthorization(mode, candidateMode, 0, p.required);
                    var balances = new LinkedHashMap<Credential, BigInteger>();
                    balances.put(credential(scripts.checkpoint()), BigInteger.ZERO);
                    balances.put(credential(scripts.module()), BigInteger.ZERO);
                    var required =
                            new ArrayList<>(
                                    List.of(
                                            scripts.state(),
                                            scripts.checkpoint(),
                                            scripts.module()));
                    if (candidate != null) {
                        required.add(candidate);
                        balances.put(credential(candidate), BigInteger.ZERO);
                    }
                    var tx =
                            new Tx()
                                    .collectFrom(
                                            List.of(
                                                    feeInput(
                                                            sponsor,
                                                            null,
                                                            BigInteger.valueOf(5000000))))
                                    .payToContract(
                                            stateInput.getAddress(),
                                            List.copyOf(stateInput.getAmount()),
                                            PlutusDataAdapter.toClientLib(AccountCodec.data(next)));
                    AccountMutation.attach(
                            tx,
                            scripts,
                            old,
                            stateInput,
                            current,
                            proposed,
                            Optional.ofNullable(candidate),
                            balances,
                            window,
                            auth);
                    return build(tx, p, sponsor, stateInput.getAddress(), required, lower, upper);
                };
        return finish(p);
    }

    /**
     * Builds a complete per-asset allocation, preserving every unallocated asset as account change.
     */
    private Plan transfer(
            Map<String, Object> body,
            String sponsor,
            AccountState state,
            Utxo stateInput,
            AccountDeployment.Scripts scripts,
            int mode,
            String locator)
            throws Exception {
        boolean consolidate = Boolean.TRUE.equals(body.get("consolidate"));
        boolean whole = Boolean.TRUE.equals(body.get("whole"));
        require(!(consolidate && whole), "Choose consolidation or whole-account transfer");
        String recipient = consolidate ? null : address(DemoServer.string(body, "recipient"));
        String account = AddressProvider.getEntAddress(scripts.asset(), NETWORK).toBech32();
        var availableInputs =
                utxos(account).stream()
                        .filter(
                                u ->
                                        u.getInlineDatum() == null
                                                && u.getDataHash() == null
                                                && u.getReferenceScriptHash() == null)
                        // Resolve in the same canonical order used by the signed Spend intent.
                        .sorted(Comparator.comparing(Utxo::getTxHash).thenComparingInt(Utxo::getOutputIndex))
                        .toList();
        var requestedInputs = body.get("inputRefs");
        List<Utxo> selected;
        if (requestedInputs == null) selected = availableInputs;
        else {
            require(requestedInputs instanceof List<?>, "inputRefs must be an array of confirmed account references");
            var references = ((List<?>) requestedInputs).stream().map(Object::toString).toList();
            require(!references.isEmpty() && references.stream().distinct().count() == references.size(), "Duplicate or empty account input selection");
            selected = availableInputs.stream().filter(u -> references.contains(u.getTxHash() + "#" + u.getOutputIndex())).toList();
            require(selected.size() == references.size(), "Selected account inputs are stale or belong to another address");
        }
        require(
                !selected.isEmpty() && selected.size() <= 16,
                "This demo supports 1–16 ordinary account inputs per transfer");
        var total = new LinkedHashMap<String, BigInteger>();
        for (var input : selected)
            for (var value : input.getAmount())
                total.merge(value.getUnit(), value.getQuantity(), BigInteger::add);
        var allocation = new LinkedHashMap<String, BigInteger>();
        if (whole) allocation.putAll(total);
        else if (!consolidate) {
            BigInteger amount;
            try {
                amount =
                        new BigDecimal(DemoServer.string(body, "amount"))
                                .movePointRight(6)
                                .toBigIntegerExact();
            } catch (Exception e) {
                throw new IllegalArgumentException("Enter an ADA amount with at most six decimals");
            }
            require(
                    amount.signum() > 0,
                    "Recipient ADA must be positive and cover its minimum output value");
            allocation.put("lovelace", amount);
            String unit = body.get("asset") instanceof String text ? text : "";
            if (!unit.isEmpty()) {
                require(
                        unit.matches("[0-9a-f]{56}([0-9a-f]{2}){0,32}"),
                        "Invalid native asset identifier");
                BigInteger quantity;
                try {
                    quantity = new BigInteger(DemoServer.string(body, "quantity"));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Token quantity must be a whole number");
                }
                require(quantity.signum() > 0, "Token quantity must be positive");
                allocation.put(unit, quantity);
            }
        }
        var change = new LinkedHashMap<>(total);
        for (var entry : allocation.entrySet()) {
            BigInteger remaining =
                    change.getOrDefault(entry.getKey(), BigInteger.ZERO).subtract(entry.getValue());
            require(remaining.signum() >= 0, "Insufficient account balance for " + entry.getKey());
            if (remaining.signum() == 0) change.remove(entry.getKey());
            else change.put(entry.getKey(), remaining);
        }
        if (!change.isEmpty())
            require(
                    change.getOrDefault("lovelace", BigInteger.ZERO)
                                    .compareTo(BigInteger.valueOf(2000000))
                            >= 0,
                    "Leave at least 2 ADA for account change, or choose the whole-account"
                            + " transfer");
        var block = backend.getBlockService().getLatestBlock().getValue();
        var configuredBudget = PeriodicBudgetCodec.isProfile(state.authConfig()) ? PeriodicBudgetCodec.decode(state.authConfig()).budget()
                .filter(budget -> budget.limit().signum() > 0) : Optional.<Budget>empty();
        long lower = block.getSlot();
        // This service is explicitly DevKit-only, whose qualified slot length is one second.
        // The reusable SDK accepts actual chain-derived interval bounds instead of making this assumption.
        long seconds = configuredBudget.isEmpty() ? 300 : Math.min(300,
                PeriodicBudgetCodec.window(BigInteger.valueOf(block.getTime() * 1000), configuredBudget.get().period())
                        .add(PeriodicBudgetCodec.duration(configuredBudget.get().period())).longValueExact() / 1000 - block.getTime());
        require(seconds > 0, "Budget window ended; refresh chain state before preparing another intent");
        long upper = lower + seconds;
        var window =
                new AccountMutation.Window(
                        BigInteger.valueOf(block.getTime() * 1000),
                        BigInteger.valueOf((block.getTime() + seconds) * 1000),
                        false);
        var recipients =
                consolidate
                        ? JulcList.<Recipient>empty()
                        : list(
                                new Recipient(
                                        BigInteger.ZERO,
                                        ledgerAddress(recipient),
                                        assetMap(allocation)));
        var action =
                new Spend(
                        list(
                                selected.stream()
                                        .map(DemoService::ref)
                                        .sorted(
                                                Comparator.comparing(
                                                        r ->
                                                                hex(r.txId().hash())
                                                                        + String.format(
                                                                                "%08x",
                                                                                r.index()
                                                                                        .intValueExact())))
                                        .toArray(TxOutRef[]::new)),
                        recipients,
                        BigInteger.ZERO);
        var intent = intent(state, stateInput, action, window);
        var counter = configuredBudget.isEmpty() ? null : PeriodicBudgetTransfer.prepare(state,
                budgetCounter(state, configuredBudget.get()), total.getOrDefault("lovelace", BigInteger.ZERO)
                        .subtract(change.getOrDefault("lovelace", BigInteger.ZERO)), window.lower(), window.upper());
        var p =
                new Plan(
                        consolidate ? "Consolidate assets" : "Send assets",
                        WireFormat.renderSigningRequest(
                                AccountCodec.data(intent),
                                AccountCodec.data(state),
                                ref(stateInput).toPlutusData(),
                                null));
        p.locator = locator;
        if (counter != null) p.review += "\nPeriodic budget: " + (configuredBudget.get().period().equals(BigInteger.TWO) ? "weekly" : "daily")
                + "; post-transaction usage " + counter.next().spent() + " / " + configuredBudget.get().limit() + " lovelace. This consumes the shared counter; competing spends may need a fresh transaction.";
        requests(
                p,
                state.authConfig(),
                selectApprovers(PolicyConfigCodec.spendPolicy(state.authConfig(), action), body.get("approvers")),
                AccountCodec.intentDigest(intent, null),
                "operation",
                mode);
        if (mode >= 2 && !consolidate && allocation.size() == 1 && allocation.containsKey("lovelace"))
            for (var request : p.requests) {
                if (request.evidence != null) continue;
                request.companionProfile = "kavach-cose-spend-v1";
                request.companionData = AccountCodec.data(intent);
            }
        feePayer(p, sponsor);
        p.build =
                () -> {
                    var proof = new Proof(BigInteger.valueOf(mode), evidence(p, "operation"));
                    var tx = new Tx();
                    if (!allocation.isEmpty()) tx.payToAddress(recipient, amounts(allocation));
                    if (!change.isEmpty()) tx.payToAddress(account, amounts(change));
                    if (counter != null) PeriodicBudgetTransfer.attach(tx, counter, AccountCodec.intentDigest(intent, null));
                    SponsorFeeProtection protection = null;
                    if (!allocation.isEmpty() && recipient.equals(sponsor)) {
                        var feeInput = utxos(sponsor).stream()
                                .filter(u -> u.getAmount().size() == 1 && u.getAmount().getFirst().getUnit().equals("lovelace")
                                        && u.getAmount().getFirst().getQuantity().compareTo(BigInteger.valueOf(5_000_000)) >= 0)
                                .sorted(Comparator.comparing(u -> u.getAmount().getFirst().getQuantity()))
                                .findFirst().orElseThrow(() -> new IllegalArgumentException("Fee wallet needs a separate ADA-only UTxO of at least 5 ADA"));
                        tx.collectFrom(List.of(feeInput));
                        tx.payToAddress(sponsor, feeInput.getAmount());
                        protection = new SponsorFeeProtection((change.isEmpty() ? 1 : 2) + (counter == null ? 0 : 1), p.required.size() + 1);
                    }
                    AccountTransfer.attach(
                            tx,
                            scripts,
                            state,
                            stateInput,
                            selected,
                            intent,
                            proof,
                            JulcList.empty(),
                            BigInteger.ZERO,
                            BigInteger.ZERO,
                            new BrowserAuthorization(mode, mode, 0, p.required));
                    return build(
                            tx,
                            p,
                            sponsor,
                            stateInput.getAddress(),
                            List.of(scripts.asset(), scripts.checkpoint(), scripts.module()),
                            lower,
                            upper,
                            protection);
                };
        return finish(p);
    }

    /** Canonical ledger asset order: lovelace, then policy bytes and asset-name bytes. */
    private static JulcList<Asset> assetMap(Map<String, BigInteger> values) {
        return list(
                values.entrySet().stream()
                        .sorted(
                                Comparator.comparing(
                                        e -> e.getKey().equals("lovelace") ? "" : e.getKey()))
                        .map(
                                e ->
                                        e.getKey().equals("lovelace")
                                                ? new Asset(new byte[0], new byte[0], e.getValue())
                                                : new Asset(
                                                        HexUtil.decodeHexString(
                                                                e.getKey().substring(0, 56)),
                                                        HexUtil.decodeHexString(
                                                                e.getKey().substring(56)),
                                                        e.getValue()))
                        .toArray(Asset[]::new));
    }

    private static List<Amount> amounts(Map<String, BigInteger> values) {
        return values.entrySet().stream().map(e -> new Amount(e.getKey(), e.getValue())).toList();
    }

    private Transaction build(
            Tx tx,
            Plan plan,
            String sponsor,
            String holder,
            List<PlutusV3Script> scripts,
            long lower,
            long upper)
            throws Exception {
        return build(tx, plan, sponsor, holder, scripts, lower, upper, null);
    }

    private Transaction build(Tx tx, Plan plan, String sponsor, String holder,
            List<PlutusV3Script> scripts, long lower, long upper, SponsorFeeProtection protection) throws Exception {
        // This UI slice has no reward-receipt allocation editor. Never silently withdraw or assume
        // zero.
        for (var script : scripts)
            if (Files.exists(PROFILES.resolve(hex(script.getScriptHash()) + ".mode"))) {
                var info = backend.getAccountService().getAccountInformation(reward(script));
                require(
                        info.isSuccessful(),
                        "Authorization reward account could not be resolved on DevKit");
                require(
                        new BigInteger(info.getValue().getWithdrawableAmount()).signum() == 0,
                        "This authorization module has positive rewards. Use the receipt-aware SDK"
                            + " flow; the demo currently supports zero-reward checkpoints only.");
            }
        var available = utxos(holder);
        for (var script : scripts)
            tx.readFrom(
                    available.stream()
                            .filter(u -> hexUnchecked(script).equals(u.getReferenceScriptHash()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Reference script is not confirmed yet; retry"
                                                            + " after confirmation")));
        var parameters = backend.getEpochService().getProtocolParameters();
        require(parameters.isSuccessful(), "DevKit protocol parameters unavailable");
        var context =
                builder.compose(tx.from(sponsor))
                        .feePayer(sponsor)
                        .collateralPayer(sponsor)
                        .additionalSignersCount(plan.required.size())
                        .withRequiredSigners(
                                plan.required.stream()
                                        .map(HexUtil::decodeHexString)
                                        .toArray(byte[][]::new))
                        .withTxEvaluator(
                                new ExecutionBudgetMargin(
                                        (cbor, inputs) ->
                                                backend.getTransactionService().evaluateTx(cbor),
                                        parameters.getValue()))
                        .preBalanceTx(
                                DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses())
                        .removeDuplicateScriptWitnesses(true);
        if (!scripts.isEmpty()) context.withReferenceScripts(scripts.toArray(PlutusV3Script[]::new));
        if (protection != null) {
            context.mergeOutputs(false)
                    .preBalanceTx((ctx, built) -> {
                        DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses().apply(ctx, built);
                        protection.capture(ctx, built);
                    })
                    .postBalanceTx(protection::balance);
        }
        if (upper > 0) context.validFrom(lower).validTo(upper);
        return context.build();
    }

    Object update(String id, String operation, Map<String, Object> body) throws Exception {
        var p = get(id);
        synchronized (p) {
            switch (operation) {
                case "companion-request", "companion-proof" -> {
                    require(p.transaction == null && p.txHash == null, "Proof collection has finished");
                    int keyId = new BigDecimal(String.valueOf(body.get("credentialId"))).intValueExact();
                    String purpose = DemoServer.string(body, "purpose");
                    var request = p.requests.stream().filter(r -> r.id == keyId && r.purpose.equals(purpose)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown proof request"));
                    require(request.companionProfile != null && request.evidence == null, "This proof is not available for companion approval");
                    if (operation.equals("companion-request")) {
                        long expires = Instant.now().toEpochMilli() + 300_000;
                        boolean genesis = request.companionProfile.equals("kavach-cose-genesis-v1");
                        if (!genesis) {
                            var envelope = ((PlutusData.ConstrData) request.companionData).fields();
                            var validity = ((PlutusData.ConstrData) envelope.get(2)).fields();
                            expires = Math.min(expires, ((PlutusData.IntData) validity.get(1)).value().longValueExact());
                        }
                        require(expires > Instant.now().toEpochMilli(), "Plan expired; prepare a fresh request");
                        request.companionExpiresAt = expires;
                        String cbor = hex(Builtins.serialiseData(request.companionData));
                        return companion.request(request.companionTicket, request.companionProfile,
                                genesis ? "" : cbor, genesis ? cbor : request.companionState == null ? null : hex(Builtins.serialiseData(request.companionState)),
                                request.key, request.id, expires, request.companionState == null ? null : request.purpose);
                    }
                    Object response = body.get("response");
                    require(response instanceof Map<?, ?>, "Phone approval JSON required");
                    @SuppressWarnings("unchecked") var approved = (Map<String, Object>) response;
                    request.evidence = CompanionExchange.verify(approved, request.companionTicket,
                            request.companionProfile, request.id, request.key, request.payload, request.companionExpiresAt);
                    try {
                        if (p.requests.stream().allMatch(r -> r.evidence != null)) p.transaction = p.build.build();
                    } catch (Exception failure) { request.evidence = null; throw failure; }
                }
                case "proofs" -> {
                    require(
                            p.transaction == null && p.txHash == null,
                            "Proof collection has finished");
                    int keyId =
                            new BigDecimal(String.valueOf(body.get("credentialId")))
                                    .intValueExact();
                    String purpose = DemoServer.string(body, "purpose");
                    var request =
                            p.requests.stream()
                                    .filter(r -> r.id == keyId && r.purpose.equals(purpose))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Unknown proof request"));
                    request.evidence =
                            BrowserSignatures.fromCip30(
                                    request.key,
                                    request.payload,
                                    DemoServer.string(body, "signature"),
                                    DemoServer.string(body, "key"),
                                    0);
                    if (p.requests.stream().allMatch(r -> r.evidence != null))
                        p.transaction = p.build.build();
                }
                case "witnesses" -> {
                    require(
                            p.transaction != null && p.txHash == null,
                            "Transaction is not ready for witnesses");
                    String cbor = DemoServer.string(body, "witnesses");
                    require(cbor.length() <= 16384, "Witness set too large");
                    var items = CborDecoder.decode(HexUtil.decodeHexString(cbor));
                    require(
                            items.size() == 1
                                    && items.getFirst() instanceof co.nstant.in.cbor.model.Map,
                            "Invalid witness set");
                    var map = (co.nstant.in.cbor.model.Map) items.getFirst();
                    require(
                            map.getKeys().size() == 1
                                    && map.get(new co.nstant.in.cbor.model.UnsignedInteger(0))
                                            != null,
                            "Only payment-key witnesses may be merged");
                    var witnesses = TransactionWitnessSet.deserialize(map).getVkeyWitnesses();
                    require(
                            witnesses != null && !witnesses.isEmpty() && witnesses.size() <= 16,
                            "Witness count");
                    byte[] digest =
                            HexUtil.decodeHexString(TransactionUtil.getTxHash(p.transaction));
                    var additions = new LinkedHashMap<String, VkeyWitness>();
                    for (var witness : witnesses) {
                        String hash = hex(BrowserSignatures.keyHash(witness.getVkey()));
                        require(p.required.contains(hash), "Unexpected signer");
                        require(
                                witness.getSignature().length == 64
                                        && Ed25519.verify(
                                                witness.getSignature(),
                                                0,
                                                witness.getVkey(),
                                                0,
                                                digest,
                                                0,
                                                digest.length),
                                "Invalid transaction witness");
                        require(additions.put(hash, witness) == null, "Duplicate witness");
                    }
                    p.witnesses.putAll(additions);
                }
                case "submit" -> {
                    require(
                            p.transaction != null
                                    && p.txHash == null
                                    && p.witnesses.keySet().containsAll(p.required),
                            "Required signatures are missing");
                    var tx = Transaction.deserialize(p.transaction.serialize());
                    if (tx.getBody().getTtl() > 0)
                        require(backend.getBlockService().getLatestBlock().getValue().getSlot() < tx.getBody().getTtl(),
                                "This transaction expired. Prepare a fresh request and collect new approvals.");
                    if (tx.getWitnessSet() == null) tx.setWitnessSet(new TransactionWitnessSet());
                    tx.getWitnessSet().setVkeyWitnesses(new ArrayList<>(p.witnesses.values()));
                    require(
                            tx.serialize().length <= 16384,
                            "Transaction exceeds ledger size limit");
                    var result = backend.getTransactionService().submitTransaction(tx.serialize());
                    require(
                            result.isSuccessful(),
                            "Ledger rejected transaction: " + result.getResponse());
                    p.txHash = result.getValue();
                }
                case "advance" -> {
                    require(p.txHash != null && p.next != null, "No next setup step");
                    require(
                            backend.getTransactionService().getTransaction(p.txHash).isSuccessful(),
                            "Wait for ledger confirmation before continuing");
                    if (p.advanced == null) p.advanced = p.next.next();
                    return view(p.advanced);
                }
                default -> throw new IllegalArgumentException("Unsupported plan operation");
            }
            return view(p);
        }
    }

    /** Keep funding identity separate from authorization, even when their keys coincide. */
    private static void feePayer(Plan plan, String address) {
        plan.feePayerAddress = address;
        plan.feePayerKeyHash = hex(payment(address));
        plan.required.add(plan.feePayerKeyHash);
    }

    private Plan finish(Plan p) throws Exception {
        require(
                plans.size() < 200,
                "Too many pending demo requests; restart after exporting locators");
        require(p.required.size() <= 16, "Required signer count exceeds protocol limit");
        if (p.transaction == null && p.requests.stream().allMatch(r -> r.evidence != null))
            p.transaction = p.build.build();
        plans.put(p.id, p);
        return p;
    }

    private Object view(Plan p) throws Exception {
        synchronized (p) {
            var out = new LinkedHashMap<String, Object>();
            out.put("id", p.id);
            out.put("title", p.title);
            out.put("review", p.review);
            out.put("requiredSigners", List.copyOf(p.required));
            if (p.feePayerKeyHash != null) out.put("feePayer", Map.of(
                    "address", p.feePayerAddress, "paymentKeyHash", p.feePayerKeyHash));
            out.put("transactionAuthoritySigners", List.copyOf(p.transactionAuthoritySigners));
            out.put("authorityApprovals", p.requests.stream().map(r -> Map.of(
                    "id", r.id, "purpose", r.purpose, "publicKey", hex(r.key),
                    "paymentKeyHash", hex(BrowserSignatures.keyHash(r.key)),
                    "method", r.signingMethod,
                    "approved", r.signingMethod == 2 ? r.evidence != null
                            : p.witnesses.containsKey(hex(BrowserSignatures.keyHash(r.key))))).toList());
            out.put("signerKeys", p.requests.stream().map(r -> Map.of(
                    "id", r.id, "publicKey", hex(r.key),
                    "paymentKeyHash", hex(BrowserSignatures.keyHash(r.key)))).distinct().toList());
            out.put(
                    "approvals",
                    p.transaction == null
                            ? p.requests.stream()
                                    .filter(r -> r.evidence != null)
                                    .map(r -> r.purpose + ":" + r.id)
                                    .toList()
                            : List.copyOf(p.witnesses.keySet()));
            out.put(
                    "status",
                    p.txHash != null
                            ? "Submitted"
                            : p.transaction == null
                                    ? "Awaiting intent proofs"
                                    : p.witnesses.keySet().containsAll(p.required)
                                            ? "Ready"
                                            : "Awaiting transaction signatures");
            out.put(
                    "payloads",
                    p.requests.stream()
                            .filter(r -> r.evidence == null)
                            .map(
                                    r ->
                                            Map.of(
                                                    "id",
                                                    r.id,
                                                    "purpose",
                                                    r.purpose,
                                                    "publicKey",
                                                    hex(r.key),
                                                    "paymentKeyHash",
                                                    hex(BrowserSignatures.keyHash(r.key)),
                                                    "payload",
                                                    hex(r.payload),
                                                    "companionSupported", r.companionProfile != null))
                            .toList());
            if (p.transaction != null) {
                out.put("transaction", p.transaction.serializeToHex());
                out.put("fee", p.transaction.getBody().getFee().toString());
            }
            if (p.txHash != null) out.put("txHash", p.txHash);
            out.put(
                    "confirmed",
                    p.txHash != null
                            && backend.getTransactionService()
                                    .getTransaction(p.txHash)
                                    .isSuccessful());
            out.put("canAdvance", p.next != null && p.txHash != null);
            if (p.locator != null) out.put("locator", p.locator);
            if (p.setupTotal > 0) { out.put("setupStep", p.setupStep); out.put("setupTotal", p.setupTotal); }
            return out;
        }
    }

    private Plan get(String id) {
        var p = plans.get(id);
        require(p != null, "Unknown or expired request");
        return p;
    }

    private void requests(
            Plan p,
            PlutusData configuration,
            List<BigInteger> ids,
            byte[] digest,
            String purpose,
            int mode) {
        for (var id : ids) {
            var key =
                    keys(config(configuration)).stream()
                            .filter(k -> k.credentialId().equals(id))
                            .findFirst()
                            .orElseThrow();
            var r = new Request(id.intValueExact(), purpose, key.publicKey(), digest);
            r.signingMethod = PolicyConfigCodec.method(configuration, id, mode);
            if (r.signingMethod == 1) {
                r.evidence = new byte[0];
                p.required.add(hex(BrowserSignatures.keyHash(key.publicKey())));
                p.transactionAuthoritySigners.add(hex(BrowserSignatures.keyHash(key.publicKey())));
            }
            p.requests.add(r);
        }
    }

    private static JulcList<Signature> evidence(Plan p, String purpose) {
        return list(
                p.requests.stream()
                        .filter(r -> r.purpose.equals(purpose))
                        .sorted(Comparator.comparingInt(r -> r.id))
                        .map(r -> new Signature(BigInteger.valueOf(r.id), r.evidence))
                        .toArray(Signature[]::new));
    }

    private static IntentEnvelope intent(
            AccountState s, Utxo input, Action action, AccountMutation.Window w) {
        return new IntentEnvelope(
                WireFormat.protocolTag(),
                new IntentDomain(
                        BigInteger.ONE,
                        s.deploymentDomain(),
                        s.accountId(),
                        s.coreBinding(),
                        s.stateVersion(),
                        ref(input)),
                new Validity(w.lower(), w.upper()),
                action);
    }

    /**
     * Reads complete pages; refuses an oversized account instead of returning a misleading partial
     * balance.
     */
    private List<Utxo> utxos(String address) throws Exception {
        var all = new ArrayList<Utxo>();
        for (int page = 1; page <= 100; page++) {
            var result = backend.getUtxoService().getUtxos(address, 100, page, OrderEnum.asc);
            require(result.isSuccessful(), "DevKit UTxO query failed");
            all.addAll(result.getValue());
            if (result.getValue().size() < 100) return all;
        }
        throw new IllegalArgumentException("Account exceeds the demo's 10,000-output query limit");
    }

    private Utxo feeInput(String address, Utxo reserved, BigInteger minimum) throws Exception {
        return utxos(address).stream()
                .filter(DemoService::plain)
                .filter(u -> reserved == null || !ref(u).equals(ref(reserved)))
                .filter(u -> lovelace(u).compareTo(minimum) >= 0)
                .max(Comparator.comparing(DemoService::lovelace))
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Insufficient separate plain ADA fee funding on DevKit"));
    }

    private static boolean plain(Utxo u) {
        return u.getAmount() != null
                && u.getAmount().size() == 1
                && u.getAmount().getFirst().getUnit().equals("lovelace")
                && u.getInlineDatum() == null
                && u.getDataHash() == null
                && u.getReferenceScriptHash() == null;
    }

    private static BigInteger lovelace(Utxo u) {
        return u.getAmount().stream()
                .filter(a -> a.getUnit().equals("lovelace"))
                .map(Amount::getQuantity)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    private static String address(String value) {
        var a =
                value.startsWith("addr")
                        ? new com.bloxbean.cardano.client.address.Address(value)
                        : new com.bloxbean.cardano.client.address.Address(
                                HexUtil.decodeHexString(value));
        require((a.getBytes()[0] & 15) == 0, "Use a testnet address");
        return a.toBech32();
    }

    private static byte[] payment(String address) {
        var a = new com.bloxbean.cardano.client.address.Address(address);
        int type = (a.getBytes()[0] & 255) >>> 4;
        require(
                type == 0 || type == 2 || type == 6,
                "A key-payment base or enterprise address is required");
        return a.getPaymentCredentialHash().orElseThrow();
    }

    private static Address ledgerAddress(String address) {
        byte[] bytes = new com.bloxbean.cardano.client.address.Address(address).getBytes();
        int type = (bytes[0] & 255) >>> 4;
        var paymentCredential = new Credential.PubKeyCredential(new PubKeyHash(payment(address)));
        if (type == 6) return new Address(paymentCredential, Optional.empty());
        require(bytes.length == 57, "Unsupported base address length");
        byte[] stake = Arrays.copyOfRange(bytes, 29, 57);
        Credential stakeCredential =
                type == 0
                        ? new Credential.PubKeyCredential(new PubKeyHash(stake))
                        : new Credential.ScriptCredential(new ScriptHash(stake));
        return new Address(
                paymentCredential, Optional.of(new StakingCredential.StakingHash(stakeCredential)));
    }

    private static TxOutRef ref(Utxo input) {
        return new TxOutRef(
                new TxId(HexUtil.decodeHexString(input.getTxHash())),
                BigInteger.valueOf(input.getOutputIndex()));
    }

    private static Credential credential(PlutusV3Script script) throws Exception {
        return new Credential.ScriptCredential(new ScriptHash(script.getScriptHash()));
    }

    private static String reward(PlutusV3Script script) throws Exception {
        return AddressProvider.getRewardAddress(script, NETWORK).toBech32();
    }

    private static int mode(Map<String, Object> body) {
        int mode = Integer.parseInt(DemoServer.string(body, "mode"));
        require(mode >= 1 && mode <= 4, "Unsupported browser signing mode");
        return mode;
    }

    private static PlutusV3Script browser(
            AccountDeployment.Scripts scripts, DeploymentDomain domain, Address sink, int mode)
            throws Exception {
        var args =
                List.of(
                        AccountCodec.data(BigInteger.ONE),
                        AccountCodec.data(BigInteger.ONE),
                        AccountCodec.data(domain),
                        PlutusData.bytes(scripts.state().getScriptHash()),
                        PlutusData.bytes(scripts.checkpoint().getScriptHash()),
                        AccountCodec.data(sink),
                        PlutusData.integer(mode));
        var script =
                JulcScriptLoader.load(
                        mode == 4 ? BudgetPolicyModule.class : mode == 3 ? PolicyModule.class : BrowserModule.class,
                        (mode >= 3 ? args.subList(0, 6) : args).stream()
                                .map(PlutusDataAdapter::toClientLib)
                                .toArray(
                                        com.bloxbean.cardano.client.plutus.spec.PlutusData[]::new));
        Files.createDirectories(PROFILES);
        Files.writeString(
                PROFILES.resolve(hex(script.getScriptHash()) + ".mode"), String.valueOf(mode));
        return script;
    }

    private static int profile(byte[] hash) throws Exception {
        Path file = PROFILES.resolve(hex(hash) + ".mode");
        require(
                Files.exists(file),
                "Unknown module deployment profile; import an independently verified demo"
                        + " deployment before signing");
        int mode = Integer.parseInt(Files.readString(file));
        require(mode >= 1 && mode <= 4, "Invalid deployment profile");
        return mode;
    }

    private static AccountDeployment.Scripts withModule(
            AccountDeployment.Scripts old, PlutusV3Script module) throws Exception {
        return new AccountDeployment.Scripts(
                old.state(),
                old.checkpoint(),
                module,
                old.nft(),
                old.asset(),
                old.accountId(),
                old.coreBinding(),
                new AuthModuleRef(module.getScriptHash(), BigInteger.ONE));
    }

    private static Ed25519Config parseKeys(String text, Object requestedPolicies) {
        var lines = text.trim().split("[\\s,]+");
        require(lines.length >= 3 && lines.length <= 8, "This setup flow requires 3 to 8 distinct public keys");
        var entries = new ArrayList<KeyEntry>();
        for (int i = 0; i < lines.length; i++) {
            require(
                    lines[i].matches("[0-9a-fA-F]{64}"),
                    "Expected a 32-byte public key, never a private key");
            entries.add(new KeyEntry(BigInteger.valueOf(i), HexUtil.decodeHexString(lines[i])));
        }
        var config =
                new Ed25519Config(
                        BigInteger.ONE,
                        list(entries.toArray(KeyEntry[]::new)),
                        policy(1, 0),
                        policy(2, 0, 1),
                        policy(1, 1),
                        policy(1, 2),
                        policy(1, 1),
                        policy(1, 2));
        if (requestedPolicies != null) {
            require(
                    requestedPolicies instanceof List<?>
                            && ((List<?>) requestedPolicies).size() == 6,
                    "Six authority policies are required");
            var policies = new ArrayList<ThresholdPolicy>();
            for (var item : (List<?>) requestedPolicies) {
                require(item instanceof Map<?, ?>, "Invalid policy");
                var fields = (Map<?, ?>) item;
                require(fields.get("members") instanceof List<?>, "Policy members required");
                var members =
                        ((List<?>) fields.get("members"))
                                .stream()
                                        .map(v -> new BigDecimal(v.toString()).toBigIntegerExact())
                                        .sorted()
                                        .toArray(BigInteger[]::new);
                policies.add(
                        new ThresholdPolicy(
                                new BigDecimal(fields.get("threshold").toString())
                                        .toBigIntegerExact(),
                                list(members)));
            }
            config =
                    new Ed25519Config(
                            BigInteger.ONE,
                            config.keys(),
                            policies.get(0),
                            policies.get(1),
                            policies.get(2),
                            policies.get(3),
                            policies.get(4),
                            policies.get(5));
        }
        WireFormat.validateConfig(AccountCodec.data(config));
        return config;
    }

    private static ThresholdPolicy policy(int threshold, int... ids) {
        return new ThresholdPolicy(
                BigInteger.valueOf(threshold),
                list(Arrays.stream(ids).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new)));
    }

    private static Ed25519Config config(PlutusData data) {
        return PolicyConfigCodec.decodeRoles(data);
    }

    /** Builds a complete signed destination configuration; never an unsigned UI-only policy. */
    private static PlutusData policyConfiguration(Map<String, Object> body, Ed25519Config current, int mode, AccountState state) throws Exception {
        String publicKeys = keys(current).stream().map(k -> hex(k.publicKey())).collect(Collectors.joining("\n"));
        var roles = body.get("policies") == null ? current : parseKeys(publicKeys, body.get("policies"));
        if (mode < 3) return AccountCodec.data(roles);
        if ("Create account".equals(body.get("action")) && !Boolean.TRUE.equals(body.get("amountTiers"))) {
            body = new LinkedHashMap<>(body);
            body.put("smallMembers", javaList(roles.spend().credentialIds()));
            body.put("smallThreshold", roles.spend().threshold().toString());
            body.put("smallPaymentAda", "0");
        }
        require(body.get("smallMembers") instanceof List<?>, "Small-payment members required");
        require(body.get("coseIds") instanceof List<?>, "COSE credential IDs required");
        var low = new ThresholdPolicy(new BigInteger(body.get("smallThreshold").toString()),
                list(((List<?>) body.get("smallMembers")).stream().map(v -> new BigInteger(v.toString())).sorted().toArray(BigInteger[]::new)));
        var methods = list(((List<?>) body.get("coseIds")).stream().map(v -> new BigInteger(v.toString())).sorted().toArray(BigInteger[]::new));
        var configuration = AccountCodec.data(new PolicyConfig(BigInteger.ONE, roles, methods,
                new BigDecimal(body.get("smallPaymentAda").toString()).movePointRight(6).toBigIntegerExact(), low));
        PolicyConfigCodec.validate(configuration);
        if (mode != 4) return configuration;
        var previous = PeriodicBudgetCodec.isProfile(state.authConfig()) ? PeriodicBudgetCodec.decode(state.authConfig()).budget() : Optional.<Budget>empty();
        String counter = body.get("budgetCounter") instanceof String c ? c : previous.map(b -> hex(b.counter().policy())).orElse("");
        boolean enabled = Boolean.TRUE.equals(body.get("budgetEnabled"));
        require(!enabled || !counter.isEmpty(), "Budget counter must be created first");
        Optional<Budget> budget = Optional.empty();
        if (!counter.isEmpty()) {
            require(counter.matches("[0-9a-f]{56}"), "Budget counter identifier");
            var manifest = PROFILES.resolve(counter + ".counter");
            require(Files.exists(manifest) && Files.readString(manifest).equals(hex(state.accountId().policy())),
                    "Counter initialization is not verified for this account; import its verified deployment record");
            String periodName = body.get("budgetPeriod") instanceof String p ? p : previous.map(b -> b.period().equals(BigInteger.TWO) ? "weekly" : "daily").orElse("daily");
            require(periodName.equals("daily") || periodName.equals("weekly"), "Choose a daily or weekly budget");
            var limit = enabled ? new BigDecimal(body.get("budgetAda").toString()).movePointRight(6).toBigIntegerExact() : BigInteger.ZERO;
            require(!enabled || limit.signum() > 0, "Enabled budget must have a positive ADA limit");
            budget = Optional.of(new Budget(new AccountId(HexUtil.decodeHexString(counter), new byte[0]),
                    periodName.equals("weekly") ? BigInteger.TWO : BigInteger.ONE, limit));
        }
        var envelope = AccountCodec.data(new Configuration(BigInteger.ONE, budget, configuration));
        PeriodicBudgetCodec.decode(envelope);
        return envelope;
    }

    /** A coordinator chooses a sufficient subset; it cannot change the authenticated threshold. */
    private static List<BigInteger> selectApprovers(ThresholdPolicy policy, Object requested) {
        var members = javaList(policy.credentialIds());
        if (requested == null || requested instanceof String text && text.isBlank())
            return members.subList(0, policy.threshold().intValueExact());
        require(requested instanceof String, "Approvers must be comma-separated credential IDs");
        var chosen =
                Arrays.stream(((String) requested).trim().split("[\\s,]+"))
                        .map(BigInteger::new)
                        .sorted()
                        .toList();
        require(
                chosen.size() >= policy.threshold().intValueExact()
                        && chosen.size() <= 8
                        && chosen.stream().distinct().count() == chosen.size()
                        && members.containsAll(chosen),
                "Selected approvers must be distinct policy members meeting its threshold");
        return chosen;
    }

    private static List<KeyEntry> keys(Ed25519Config config) {
        return javaList(config.keys());
    }

    private static List<BigInteger> allIds(Ed25519Config config) {
        return keys(config).stream().map(KeyEntry::credentialId).toList();
    }

    private static List<BigInteger> introduced(Ed25519Config old, Ed25519Config target) {
        var previous = keys(old).stream().map(k -> hex(k.publicKey())).collect(Collectors.toSet());
        return keys(target).stream()
                .filter(k -> !previous.contains(hex(k.publicKey())))
                .map(KeyEntry::credentialId)
                .toList();
    }

    private static <T> List<T> javaList(JulcList<T> values) {
        var result = new ArrayList<T>();
        for (var value : values) result.add(value);
        return result;
    }

    @SafeVarargs
    private static <T> JulcList<T> list(T... values) {
        var result = JulcList.<T>empty();
        for (int i = values.length - 1; i >= 0; i--) result = result.prepend(values[i]);
        return result;
    }

    private static String hex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }

    private static String hexUnchecked(PlutusV3Script script) {
        try {
            return hex(script.getScriptHash());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid script", e);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
