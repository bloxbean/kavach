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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
        final UUID companionTicket = UUID.randomUUID();
        String companionProfile;
        PlutusData companionData;
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
        Transaction transaction;
        Build build;
        Next next;
        Plan advanced;
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
                List.of("CIP-30 transaction", "Bounded CIP-8 / COSE"));
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
        value.put("balance", amounts.getOrDefault("lovelace", BigInteger.ZERO).toString());
        value.put(
                "assets",
                amounts.entrySet().stream()
                        .filter(e -> !e.getKey().equals("lovelace"))
                        .map(e -> Map.of("unit", e.getKey(), "quantity", e.getValue().toString()))
                        .toList());
        var config = config(state.authConfig());
        value.put(
                "keys",
                keys(config).stream()
                        .map(
                                k ->
                                        Map.of(
                                                "id",
                                                k.credentialId().intValueExact(),
                                                "publicKey",
                                                hex(k.publicKey())))
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
            var module = browser(scripts, domain, sink, mode);
            setup.scripts = withModule(scripts, module);
            setup.state =
                    AccountDeployment.genesis(
                            setup.scripts,
                            domain,
                            AccountCodec.data(config),
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
        require(
                state.deploymentDomain().networkMagic().equals(BigInteger.valueOf(42)),
                "Locator is not for this DevKit deployment");
        if (action.equals("Send assets"))
            return view(transfer(body, sponsor, state, restored.input(), scripts, mode, locator));
        Action mutation =
                switch (action) {
                    case "Freeze account" -> new Freeze();
                    case "Unfreeze account" -> new Unfreeze();
                    case "Rotate keys" ->
                            new ReplaceConfig(
                                    AccountCodec.data(
                                            parseKeys(
                                                    DemoServer.string(body, "target"),
                                                    body.get("policies"))));
                    case "Start recovery" ->
                            new StartRecovery(
                                    state.recoverySequence().add(BigInteger.ONE),
                                    AccountCodec.data(
                                            parseKeys(
                                                    DemoServer.string(body, "target"),
                                                    body.get("policies"))));
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
                                state.authConfig());
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
                            + "Full development setup: five 80 ADA references (400 ADA permanently"
                            + " locked), 12 ADA account state, registration deposits and"
                            + " transaction fees. Use disposable DevKit assets only.";
            p.next = () -> publishSetup(setup, index + 1);
            return p;
        }
        if (index == 5) {
            var p =
                    registration(
                            setup.sponsor,
                            List.of(setup.scripts.checkpoint(), setup.scripts.module()),
                            setup.seed);
            p.next = () -> publishSetup(setup, 6);
            return p;
        }
        var p =
                new Plan(
                        "Create account · approve genesis",
                        WireFormat.renderState(AccountCodec.data(setup.state)));
        p.locator = AccountLocator.fromState(setup.state).backup();
        var digest = WireFormat.digest(ProofDomains.genesis(AccountCodec.data(setup.state)));
        requests(
                p,
                config(setup.state.authConfig()),
                allIds(config(setup.state.authConfig())),
                digest,
                "genesis",
                setup.mode);
        if (setup.mode == 2) for (var request : p.requests) {
            request.companionProfile = "kavach-cose-genesis-v1";
            request.companionData = AccountCodec.data(setup.state);
        }
        p.required.add(hex(payment(setup.sponsor)));
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
        return finish(p);
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
        p.required.add(hex(payment(sponsor)));
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
        p.required.add(hex(payment(sponsor)));
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
                    config,
                    selectApprovers(policy, requestedApprovers),
                    AccountCodec.intentDigest(intent, null),
                    "operation",
                    mode);
        }
        if (action instanceof ReplaceConfig replacement) {
            var target = config(replacement.newConfig());
            requests(
                    p,
                    target,
                    introduced(config, target),
                    WireFormat.digest(
                            ProofDomains.configuration(
                                    AccountCodec.data(old), AccountCodec.data(intent))),
                    "possession",
                    mode);
        }
        if (action instanceof ReplaceModule replacement)
            requests(
                    p,
                    config(replacement.newConfig()),
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
                    target,
                    List.copyOf(ids),
                    WireFormat.digest(
                            ProofDomains.target(AccountCodec.data(old), AccountCodec.data(intent))),
                    "possession",
                    mode);
        }
        p.required.add(hex(payment(sponsor)));
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
        var selected =
                utxos(account).stream()
                        .filter(
                                u ->
                                        u.getInlineDatum() == null
                                                && u.getDataHash() == null
                                                && u.getReferenceScriptHash() == null)
                        .toList();
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
        long lower = block.getSlot(), upper = lower + 300;
        var window =
                new AccountMutation.Window(
                        BigInteger.valueOf(block.getTime() * 1000),
                        BigInteger.valueOf((block.getTime() + 300) * 1000),
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
        var p =
                new Plan(
                        consolidate ? "Consolidate assets" : "Send assets",
                        WireFormat.renderSigningRequest(
                                AccountCodec.data(intent),
                                AccountCodec.data(state),
                                ref(stateInput).toPlutusData(),
                                null));
        p.locator = locator;
        requests(
                p,
                config(state.authConfig()),
                selectApprovers(config(state.authConfig()).spend(), body.get("approvers")),
                AccountCodec.intentDigest(intent, null),
                "operation",
                mode);
        if (mode == 2 && !consolidate && allocation.size() == 1 && allocation.containsKey("lovelace"))
            for (var request : p.requests) {
                request.companionProfile = "kavach-cose-spend-v1";
                request.companionData = AccountCodec.data(intent);
            }
        p.required.add(hex(payment(sponsor)));
        p.build =
                () -> {
                    var proof = new Proof(BigInteger.valueOf(mode), evidence(p, "operation"));
                    var tx = new Tx();
                    if (!allocation.isEmpty()) tx.payToAddress(recipient, amounts(allocation));
                    if (!change.isEmpty()) tx.payToAddress(account, amounts(change));
                    SponsorFeeProtection protection = null;
                    if (!allocation.isEmpty() && recipient.equals(sponsor)) {
                        var feeInput = utxos(sponsor).stream()
                                .filter(u -> u.getAmount().size() == 1 && u.getAmount().getFirst().getUnit().equals("lovelace")
                                        && u.getAmount().getFirst().getQuantity().compareTo(BigInteger.valueOf(5_000_000)) >= 0)
                                .sorted(Comparator.comparing(u -> u.getAmount().getFirst().getQuantity()))
                                .findFirst().orElseThrow(() -> new IllegalArgumentException("Fee wallet needs a separate ADA-only UTxO of at least 5 ADA"));
                        tx.collectFrom(List.of(feeInput));
                        tx.payToAddress(sponsor, feeInput.getAmount());
                        protection = new SponsorFeeProtection(change.isEmpty() ? 1 : 2, p.required.size() + 1);
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
                        .withReferenceScripts(scripts.toArray(PlutusV3Script[]::new))
                        .preBalanceTx(
                                DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses())
                        .removeDuplicateScriptWitnesses(true);
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
                        require(expires > Instant.now().toEpochMilli(), "Plan expired; prepare a fresh payment");
                        request.companionExpiresAt = expires;
                        String cbor = hex(Builtins.serialiseData(request.companionData));
                        return companion.request(request.companionTicket, request.companionProfile,
                                genesis ? "" : cbor, genesis ? cbor : null, request.key, request.id, expires);
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
            Ed25519Config config,
            List<BigInteger> ids,
            byte[] digest,
            String purpose,
            int mode) {
        for (var id : ids) {
            var key =
                    keys(config).stream()
                            .filter(k -> k.credentialId().equals(id))
                            .findFirst()
                            .orElseThrow();
            var r = new Request(id.intValueExact(), purpose, key.publicKey(), digest);
            if (mode == 1) {
                r.evidence = new byte[0];
                p.required.add(hex(BrowserSignatures.keyHash(key.publicKey())));
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
        require(mode == 1 || mode == 2, "Unsupported browser signing mode");
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
                        BrowserModule.class,
                        args.stream()
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
        require(mode == 1 || mode == 2, "Invalid deployment profile");
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
        require(lines.length == 3, "This setup flow requires three distinct public keys");
        var entries = new ArrayList<KeyEntry>();
        for (int i = 0; i < 3; i++) {
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
        var f = ((PlutusData.ConstrData) data).fields();
        var entries = new ArrayList<KeyEntry>();
        for (var item : ((PlutusData.ListData) f.get(1)).items()) {
            var e = ((PlutusData.ConstrData) item).fields();
            entries.add(
                    new KeyEntry(
                            ((PlutusData.IntData) e.get(0)).value(),
                            ((PlutusData.BytesData) e.get(1)).value()));
        }
        var policies = new ArrayList<ThresholdPolicy>();
        for (int i = 2; i < 8; i++) {
            var p = ((PlutusData.ConstrData) f.get(i)).fields();
            policies.add(
                    new ThresholdPolicy(
                            ((PlutusData.IntData) p.get(0)).value(),
                            list(
                                    ((PlutusData.ListData) p.get(1))
                                            .items().stream()
                                                    .map(x -> ((PlutusData.IntData) x).value())
                                                    .toArray(BigInteger[]::new))));
        }
        return new Ed25519Config(
                BigInteger.ONE,
                list(entries.toArray(KeyEntry[]::new)),
                policies.get(0),
                policies.get(1),
                policies.get(2),
                policies.get(3),
                policies.get(4),
                policies.get(5));
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
