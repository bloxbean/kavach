package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.DuplicateScriptWitnessChecker;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Module;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountDeployment;
import com.bloxbean.cardano.kavach.sdk.AccountMutation;
import com.bloxbean.cardano.kavach.sdk.AccountTransfer;
import com.bloxbean.cardano.kavach.sdk.AccountLocator;
import com.bloxbean.cardano.kavach.sdk.ExecutionBudgetMargin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Short real-ledger lifecycle gate; this does not substitute for delayed recovery completion. */
@Tag("devkit")
@Timeout(600)
class AccountLifecycleDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private QuickTxBuilder builder = new QuickTxBuilder(backend);
    private Account sponsor = new Account(NETWORK);
    private final Account creator = new Account(NETWORK);
    private final AccountFixtures keys = new AccountFixtures();
    private Path evidencePath = Path.of("build/phase2/lifecycle/short-lifecycle.json");
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final Map<String, Utxo> references = new LinkedHashMap<>();
    private AccountDeployment.Scripts scripts;
    private AccountState state;
    private Utxo stateInput;
    private String holder;
    private String locatorBackup;
    private PlutusV3Script candidateForMutation;

    AccountLifecycleDevkitTest() throws Exception {}

    @Test void freezeUnfreezeConfigureInitiateCancelThroughSdk() throws Exception {
        initialize();
        mutate("freeze", new Freeze(), 1);
        assertInstanceOf(Frozen.class, state.mode());
        mutate("unfreeze", new Unfreeze(), 2);
        mutate("configuration", new ReplaceConfig(state.authConfig()), 0, 1);
        mutate("recovery-start", new StartRecovery(BigInteger.ONE, state.authConfig()), 1);
        var pending = assertInstanceOf(RecoveryPending.class, state.mode());
        mutate("recovery-cancel", new CancelRecovery(BigInteger.ONE, pending.proposalCommitment()), 2);
        assertInstanceOf(Frozen.class, state.mode());
        restoreWithNewProviderAndCollateral();
        mutate("unfreeze-after-cancel", new Unfreeze(), 2);
        assertInstanceOf(Normal.class, state.mode());
        assertEquals(BigInteger.valueOf(6), state.stateVersion());
        assertEquals(BigInteger.ONE, state.recoverySequence());
        assertTrue(state.recoveryNotBefore().signum() > 0);
        evidence.put("scope", "Phase 2 short lifecycle; no delayed completion or production qualification");
        persist();
    }

    /** Replaces every key under old administration and exercises both new defensive roles on ledger. */
    @Test void rotateEveryKeyUnderOldAdministration() throws Exception {
        evidencePath = Path.of("build/phase2/lifecycle/key-rotation.json");
        initialize();
        var target = new AccountFixtures();
        mutate("replace-all-keys", new ReplaceConfig(AccountCodec.data(target.config)), target, 0, 1);
        for (int id = 0; id < keys.keys.size(); id++) keys.keys.set(id, target.keys.get(id));
        mutate("new-guardian-freeze", new Freeze(), 1);
        mutate("new-defensive-unfreeze", new Unfreeze(), 2);
        transferWithCurrentSpendKey("rotated-key-transfer");
        assertEquals(AccountCodec.data(target.config), state.authConfig());
        assertInstanceOf(Normal.class, state.mode());
        evidence.put("scope", "All-new key possession under old administration, followed by new-key defensive actions on DevKit");
        persist();
    }

    /** Installs a separately parameterized module under old administration and candidate possession. */
    @Test void replaceModuleThenUseCandidateAuthority() throws Exception {
        evidencePath = Path.of("build/phase2/lifecycle/module-replacement.json");
        initialize();
        var candidateSinkAccount = new Account(NETWORK);
        var candidateSink = new Address(new Credential.PubKeyCredential(new PubKeyHash(
                candidateSinkAccount.hdKeyPair().getPublicKey().getKeyHash())), Optional.empty());
        candidateForMutation = AccountFixtures.load(Ed25519Module.class, BigInteger.ONE, BigInteger.ONE,
                state.deploymentDomain(), scripts.state().getScriptHash(), scripts.checkpoint().getScriptHash(), candidateSink);
        var candidate = candidateForMutation;
        String publication = submit(builder.compose(new Tx().payToAddress(holder, Amount.ada(80), candidate).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "candidate-publication");
        references.put(HexUtil.encodeHexString(candidate.getScriptHash()), awaitUtxos(holder, references.size() + 2).stream()
                .filter(u -> u.getTxHash().equals(publication)).findFirst().orElseThrow());
        submit(builder.compose(new Tx().registerStakeAddress(AddressProvider.getRewardAddress(candidate, NETWORK).toBech32()).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "candidate-registration");
        topUp(sponsor.baseAddress(), 20); awaitUtxos(sponsor.baseAddress(), 2);
        var target = new AccountFixtures();
        mutate("replace-module", new ReplaceModule(new AuthModuleRef(candidate.getScriptHash(), BigInteger.ONE),
                AccountCodec.data(target.config)), target, 0, 1);
        scripts = new AccountDeployment.Scripts(scripts.state(), scripts.checkpoint(), candidate, scripts.nft(), scripts.asset(),
                state.accountId(), state.coreBinding(), state.authModule());
        candidateForMutation = null;
        for (int id = 0; id < keys.keys.size(); id++) keys.keys.set(id, target.keys.get(id));
        mutate("candidate-freeze", new Freeze(), 1);
        mutate("candidate-unfreeze", new Unfreeze(), 2);
        transferWithCurrentSpendKey("candidate-transfer");
        assertInstanceOf(Normal.class, state.mode());
        assertArrayEquals(candidate.getScriptHash(), state.authModule().scriptHash());
        evidence.put("scope", "Old administration plus distinct candidate possession; new candidate authorizes subsequent defensive actions");
        persist();
    }

    /** Waits for the immutable real-ledger delay; keys stay in this process, never in evidence. */
    @Test @Tag("phase2DelayedRecovery") @Timeout(value = 26, unit = TimeUnit.HOURS)
    void completeRecoveryAfterActualMinimumDelay() throws Exception {
        evidencePath = Path.of("build/phase2/recovery/delayed-recovery.json");
        initialize();
        var target = new AccountFixtures();
        mutate("recovery-start", new StartRecovery(BigInteger.ONE, AccountCodec.data(target.config)), 1);
        var pending = assertInstanceOf(RecoveryPending.class, state.mode());
        evidence.put("pending", Map.of("executeAfter", pending.executeAfter(), "locator", locatorBackup,
                "note", "Public locator only; signing keys remain in the live worker. Do not restart this worker."));
        persist();
        System.out.println("Recovery pending until ledger time " + pending.executeAfter());
        // The target is independent of every old key. Restore using only the public locator
        // and a fresh sponsor; discard all old signing-key references before completing.
        restoreWithNewProviderAndCollateral();
        for (int id = 0; id < keys.keys.size(); id++) keys.keys.set(id, target.keys.get(id));
        while (true) {
            var block = backend.getBlockService().getLatestBlock();
            assertTrue(block.isSuccessful(), "DevKit required during recovery wait");
            if (BigInteger.valueOf(block.getValue().getTime() * 1000).compareTo(pending.executeAfter()) >= 0) break;
            Thread.sleep(30000);
        }
        mutate("recovery-complete", new CompleteRecovery(BigInteger.ONE, pending.targetConfig()), 0, 1, 2);
        assertInstanceOf(Normal.class, state.mode());
        assertEquals(AccountCodec.data(target.config), state.authConfig());
        assertEquals(BigInteger.TWO, state.stateVersion());
        evidence.put("scope", "Actual minimum-delay completion with all-new target keys and fresh collateral; rewards and post-recovery transfer require separate evidence");
        persist();
    }

    /** Creates a disposable account and publishes every mandatory reference script. */
    private void initialize() throws Exception {
        assertTrue(backend.getEpochService().getProtocolParameters().isSuccessful(), "DevKit required");
        topUp(sponsor.baseAddress(), 1000); topUp(sponsor.baseAddress(), 20); topUp(creator.baseAddress(), 20);
        awaitUtxos(sponsor.baseAddress(), 2);
        var seed = awaitUtxos(creator.baseAddress(), 1).getFirst();
        byte[] discriminator = new byte[32]; new SecureRandom().nextBytes(discriminator);
        var domain = new DeploymentDomain(BigInteger.ZERO, BigInteger.valueOf(42), discriminator);
        var sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(sponsor.hdKeyPair().getPublicKey().getKeyHash())), Optional.empty());
        scripts = AccountDeployment.derive(domain, ref(seed), creator.hdKeyPair().getPublicKey().getKeyHash(), sink, sink);
        state = AccountDeployment.genesis(scripts, domain, AccountCodec.data(keys.config), BigInteger.valueOf(86400000), BigInteger.valueOf(3600000));
        locatorBackup = AccountLocator.fromState(state).backup();
        holder = AddressProvider.getEntAddress(scripts.state(), NETWORK).toBech32();
        for (var script : List.of(scripts.state(), scripts.checkpoint(), scripts.module(), scripts.nft(), scripts.asset())) {
            String hash = HexUtil.encodeHexString(script.getScriptHash());
            String id = submit(builder.compose(new Tx().payToAddress(holder, Amount.ada(80), script).from(sponsor.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "publication-" + references.size());
            references.put(hash, awaitUtxos(holder, references.size() + 1).stream().filter(u -> u.getTxHash().equals(id)).findFirst().orElseThrow());
        }
        String coreReward = AddressProvider.getRewardAddress(scripts.checkpoint(), NETWORK).toBech32();
        String moduleReward = AddressProvider.getRewardAddress(scripts.module(), NETWORK).toBech32();
        submit(builder.compose(new Tx().registerStakeAddress(coreReward).registerStakeAddress(moduleReward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "registration");
        var digest = WireFormat.digest(ProofDomains.genesis(AccountCodec.data(state)));
        var possession = new ArrayList<Signature>();
        for (int id = 0; id < keys.keys.size(); id++) possession.add(AccountFixtures.sign(id, keys.keys.get(id), digest));
        var genesis = new GenesisModuleRedeemer(BigInteger.ONE, state, AccountFixtures.list(possession.toArray(Signature[]::new)), JulcList.empty());
        String policy = HexUtil.encodeHexString(state.accountId().policy());
        var creation = new Tx().collectFrom(List.of(seed))
                .mintAsset(scripts.nft(), new com.bloxbean.cardano.client.transaction.spec.Asset("", BigInteger.ONE), BigIntPlutusData.of(0))
                .payToContract(holder, List.of(Amount.ada(12), Amount.asset(policy, "", 1)), PlutusDataAdapter.toClientLib(AccountCodec.data(state)))
                .attachRewardValidator(scripts.module()).withdraw(moduleReward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(AccountCodec.data(genesis)))
                .readFrom(reference(scripts.nft())).readFrom(reference(scripts.module())).from(creator.baseAddress());
        var signed = builder.compose(creation).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withRequiredSigners(creator.hdKeyPair().getPublicKey().getKeyHash()).withSigner(SignerProviders.signerFrom(creator)).withSigner(SignerProviders.signerFrom(sponsor))
                .withTxEvaluator(evaluator()).withReferenceScripts(scripts.nft(), scripts.module())
                .preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses()).removeDuplicateScriptWitnesses(true).buildAndSign();
        String id = submit(signed, "creation");
        stateInput = findState(id);
        // Publication/creation may consolidate the original faucet outputs. Re-establish
        // a separate disposable collateral UTxO before selecting explicit mutation fees.
        topUp(sponsor.baseAddress(), 20);
        awaitUtxos(sponsor.baseAddress(), 2);
    }

    /** Recreates the client from the locator and surviving defensive key, discarding old caches and sponsor. */
    private void restoreWithNewProviderAndCollateral() throws Exception {
        state = null; stateInput = null; scripts = null; holder = null; references.clear();
        keys.keys.set(0, null); // Lost primary signing key; the independent unfreeze key survives.
        backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
        builder = new QuickTxBuilder(backend);
        sponsor = new Account(NETWORK);
        var restored = AccountLocator.parse(locatorBackup).restore(AccountLocator.provider(backend));
        state = restored.state(); stateInput = restored.input(); holder = stateInput.getAddress();
        assertTrue(state.mode() instanceof Frozen || state.mode() instanceof RecoveryPending);
        scripts = AccountDeployment.restore(state, backend);
        for (var output : awaitUtxos(holder, 6)) if (output.getReferenceScriptHash() != null)
            references.put(output.getReferenceScriptHash(), output);
        for (var script : List.of(scripts.state(), scripts.checkpoint(), scripts.module(), scripts.nft(), scripts.asset()))
            assertNotNull(reference(script), "Restored published reference");
        topUp(sponsor.baseAddress(), 100); topUp(sponsor.baseAddress(), 20);
        awaitUtxos(sponsor.baseAddress(), 2);
        evidence.put("restoration", Map.of("locator", locatorBackup, "stateTx", stateInput.getTxHash(),
                "version", state.stateVersion(), "newCollateralAddress", sponsor.baseAddress(), "primaryKeyDiscarded", true));
        persist();
    }

    /** Signs semantic actions with old authorities and uses node-evaluated budgets for the actual transaction. */
    private void mutate(String name, Action action, int... signers) throws Exception {
        mutate(name, action, null, signers);
    }

    /** Adds independent possession proofs when every registry key is replaced. */
    private void mutate(String name, Action action, AccountFixtures replacementKeys, int... signers) throws Exception {
        var latest = backend.getBlockService().getLatestBlock().getValue();
        long lower = latest.getSlot(), upper = lower + 180;
        var window = new AccountMutation.Window(BigInteger.valueOf(latest.getTime() * 1000), BigInteger.valueOf((latest.getTime() + 180) * 1000), false);
        var request = new IntentEnvelope(WireFormat.protocolTag(), new IntentDomain(BigInteger.ONE, state.deploymentDomain(),
                state.accountId(), state.coreBinding(), state.stateVersion(), ref(stateInput)), new Validity(window.lower(), window.upper()), action);
        var digest = action instanceof CompleteRecovery
                ? WireFormat.digest(ProofDomains.target(AccountCodec.data(state), AccountCodec.data(request)))
                : AccountCodec.intentDigest(request, null);
        var proofs = new ArrayList<Signature>();
        for (int id : signers) proofs.add(AccountFixtures.sign(id, keys.keys.get(id), digest));
        var signatures = AccountFixtures.list(proofs.toArray(Signature[]::new));
        var approval = action instanceof CompleteRecovery
                ? new ModuleRedeemer(BigInteger.ONE, request, Optional.empty(), signatures, JulcList.empty())
                : new ModuleRedeemer(BigInteger.ONE, request, Optional.of(new Proof(BigInteger.ZERO,
                        signatures)), JulcList.empty(), JulcList.empty());
        Optional<ModuleRedeemer> candidateApproval = Optional.empty();
        if (replacementKeys != null) {
            var possessionDigest = WireFormat.digest(ProofDomains.configuration(AccountCodec.data(state), AccountCodec.data(request)));
            var possession = new ArrayList<Signature>();
            for (int id = 0; id < replacementKeys.keys.size(); id++)
                possession.add(AccountFixtures.sign(id, replacementKeys.keys.get(id), possessionDigest));
            var newProofs = AccountFixtures.list(possession.toArray(Signature[]::new));
            if (action instanceof ReplaceModule)
                candidateApproval = Optional.of(new ModuleRedeemer(BigInteger.ONE, request, Optional.empty(), newProofs, JulcList.empty()));
            else approval = new ModuleRedeemer(BigInteger.ONE, request, approval.operationProof(), newProofs, JulcList.empty());
        }
        var prepared = AccountAdministration.prepare(state, approval, candidateApproval, window.lower(), window.upper(), false);
        // Seed a real plain fee input before CCL's first script evaluation. Otherwise its
        // provisional sponsor-change output has zero ADA, which the state validator rejects.
        var feeInput = awaitUtxos(sponsor.baseAddress(), 2).stream().max(Comparator.comparing(u -> u.getAmount().stream()
                .filter(a -> a.getUnit().equals("lovelace")).findFirst().orElseThrow().getQuantity())).orElseThrow();
        var tx = new Tx().collectFrom(List.of(feeInput)).payToContract(holder, List.copyOf(stateInput.getAmount()), PlutusDataAdapter.toClientLib(AccountCodec.data(prepared.successor())))
                .readFrom(reference(scripts.state())).readFrom(reference(scripts.checkpoint())).readFrom(reference(scripts.module()));
        var balances = new LinkedHashMap<Credential, BigInteger>();
        balances.put(credential(scripts.checkpoint()), BigInteger.ZERO); balances.put(credential(scripts.module()), BigInteger.ZERO);
        var requiredScripts = new ArrayList<>(List.of(scripts.state(), scripts.checkpoint(), scripts.module()));
        if (candidateForMutation != null) {
            balances.put(credential(candidateForMutation), BigInteger.ZERO);
            tx.readFrom(reference(candidateForMutation)); requiredScripts.add(candidateForMutation);
        }
        AccountMutation.attach(tx, scripts, state, stateInput, approval, candidateApproval, Optional.ofNullable(candidateForMutation), balances, window);
        var unsigned = builder.compose(tx).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withTxEvaluator(evaluator()).validFrom(lower).validTo(upper).withReferenceScripts(requiredScripts.toArray(PlutusV3Script[]::new))
                .preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses()).removeDuplicateScriptWitnesses(true).build();
        var signed = sponsor.sign(unsigned);
        String id = submit(signed, name);
        stateInput = findState(id); state = prepared.successor();
        var actual = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexUtil.decodeHexString(stateInput.getInlineDatum())));
        assertEquals(AccountCodec.data(state), actual, "Confirmed successor datum");
        evidence.put(name + "-state", stateInput.getInlineDatum());
        persist();
    }

    /** Funds ordinary custody and proves a transfer under the currently installed spend authority. */
    private void transferWithCurrentSpendKey(String name) throws Exception {
        String address = AddressProvider.getEntAddress(scripts.asset(), NETWORK).toBech32();
        String funding = submit(builder.compose(new Tx().payToAddress(address, Amount.ada(20)).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), name + "-funding");
        var asset = awaitUtxos(address, 1).stream().filter(u -> u.getTxHash().equals(funding)).findFirst().orElseThrow();
        var recipient = new Account(NETWORK);
        var destination = new Address(new Credential.PubKeyCredential(new PubKeyHash(recipient.hdKeyPair().getPublicKey().getKeyHash())), Optional.empty());
        var latest = backend.getBlockService().getLatestBlock().getValue();
        long lower = latest.getSlot(), upper = lower + 180;
        var action = new Spend(AccountFixtures.list(ref(asset)), AccountFixtures.list(new Recipient(BigInteger.ZERO, destination,
                AccountFixtures.list(new Asset(new byte[0], new byte[0], BigInteger.valueOf(2000000))))), BigInteger.ZERO);
        var intent = new IntentEnvelope(WireFormat.protocolTag(), new IntentDomain(BigInteger.ONE, state.deploymentDomain(), state.accountId(),
                state.coreBinding(), state.stateVersion(), ref(stateInput)),
                new Validity(BigInteger.valueOf(latest.getTime() * 1000), BigInteger.valueOf((latest.getTime() + 180) * 1000)), action);
        var proof = new Proof(BigInteger.ZERO, AccountFixtures.list(AccountFixtures.sign(0, keys.keys.get(0), AccountCodec.intentDigest(intent, null))));
        var tx = new Tx().payToAddress(recipient.enterpriseAddress(), Amount.ada(2)).payToAddress(address, Amount.ada(18))
                .readFrom(reference(scripts.asset())).readFrom(reference(scripts.checkpoint())).readFrom(reference(scripts.module()));
        AccountTransfer.attach(tx, scripts, state, stateInput, List.of(asset), intent, proof, JulcList.empty(), BigInteger.ZERO, BigInteger.ZERO);
        var unsigned = builder.compose(tx).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withTxEvaluator(evaluator()).validFrom(lower).validTo(upper).withReferenceScripts(scripts.asset(), scripts.checkpoint(), scripts.module())
                .preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses()).removeDuplicateScriptWitnesses(true).build();
        String spent = submit(sponsor.sign(unsigned), name);
        assertTrue(awaitUtxos(recipient.enterpriseAddress(), 1).stream().anyMatch(u -> u.getTxHash().equals(spent)), "Recipient received confirmed transfer");
    }

    private Utxo findState(String transaction) throws Exception {
        String policy = HexUtil.encodeHexString(state.accountId().policy());
        return awaitUtxos(holder, references.size() + 1).stream().filter(u -> u.getTxHash().equals(transaction)
                && u.getAmount().stream().anyMatch(a -> a.getUnit().equals(policy) && a.getQuantity().equals(BigInteger.ONE))).findFirst().orElseThrow();
    }
    private Utxo reference(PlutusV3Script script) throws Exception { return references.get(HexUtil.encodeHexString(script.getScriptHash())); }
    private static Credential credential(PlutusV3Script script) throws Exception { return new Credential.ScriptCredential(new ScriptHash(script.getScriptHash())); }
    private static TxOutRef ref(Utxo input) { return new TxOutRef(new TxId(HexUtil.decodeHexString(input.getTxHash())), BigInteger.valueOf(input.getOutputIndex())); }
    private TransactionEvaluator evaluator() throws Exception {
        var parameters = backend.getEpochService().getProtocolParameters(); assertTrue(parameters.isSuccessful());
        return new ExecutionBudgetMargin((cbor, inputs) -> {
            var result = backend.getTransactionService().evaluateTx(cbor);
            if (!result.isSuccessful()) {
                try {
                    Files.createDirectories(evidencePath.getParent());
                    Files.writeString(evidencePath.resolveSibling("failed-evaluation.cborhex"), HexUtil.encodeHexString(cbor));
                    Files.writeString(evidencePath.resolveSibling("failed-evaluation.json"), JsonUtil.getPrettyJson(Transaction.deserialize(cbor)));
                } catch (Exception failure) { throw new IllegalStateException("Could not preserve public evaluation failure", failure); }
            }
            return result;
        }, parameters.getValue());
    }
    /** Persists only public results after each confirmed transaction, including partial-run evidence. */
    private String submit(Transaction tx, String name) throws Exception {
        assertTrue(tx.serialize().length <= 16384, name + " transaction bytes");
        var result = backend.getTransactionService().submitTransaction(tx.serialize()); assertTrue(result.isSuccessful(), name + ": " + result);
        for (int attempt = 0; attempt < 60; attempt++) {
            var confirmed = backend.getTransactionService().getTransaction(result.getValue());
            if (confirmed.isSuccessful() && confirmed.getValue() != null) {
                evidence.put(name, Map.of("tx", result.getValue(), "bytes", tx.serialize().length, "fee", tx.getBody().getFee(),
                        "redeemers", tx.getWitnessSet().getRedeemers() == null ? List.of() : tx.getWitnessSet().getRedeemers()));
                persist(); System.out.println(name + " confirmed " + result.getValue() + " fee=" + tx.getBody().getFee());
                return result.getValue();
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Unconfirmed " + name + ": " + result.getValue());
    }
    private void persist() throws Exception { Files.createDirectories(evidencePath.getParent()); Files.writeString(evidencePath, JsonUtil.getPrettyJson(evidence)); }
    private List<Utxo> awaitUtxos(String address, int minimum) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            var result = backend.getUtxoService().getUtxos(address, 100, 1, OrderEnum.desc);
            if (result.isSuccessful() && result.getValue().size() >= minimum) return result.getValue();
            Thread.sleep(1000);
        }
        throw new AssertionError("DevKit UTxOs unavailable at " + address);
    }
    private void topUp(String address, long ada) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:10000/local-cluster/api/addresses/topup"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"address\":\"" + address + "\",\"adaAmount\":" + ada + "}")).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); assertFalse(response.body().contains("\"status\":false"));
        }
    }
}
