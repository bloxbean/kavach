package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Disposable local devnet funds only: the holder permanently locks its deposits. */
@Tag("devkit")
@Timeout(240)
class StateIdentityProbeDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account creator = new Account(NETWORK);

    @Test
    void oneShotInitializationAndAuthenticatedReferenceOnLedger() throws Exception {
        var parameters = backend.getEpochService().getProtocolParameters();
        assertTrue(parameters.isSuccessful(), "DevKit protocol parameters unavailable");
        assertEquals(11, parameters.getValue().getProtocolMajorVer());
        topUp(sponsor.baseAddress(), 100);
        topUp(sponsor.baseAddress(), 10);
        topUp(creator.baseAddress(), 20);
        awaitUtxos(sponsor.baseAddress(), 2);
        var seed = awaitUtxos(creator.baseAddress(), 1).getFirst();
        byte[] creatorHash = creator.hdKeyPair().getPublicKey().getKeyHash();
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        var holder = StateProbeFixtures.holder();
        String holderAddress = AddressProvider.getEntAddress(holder, NETWORK).toBech32();
        var policy = StateProbeFixtures.mint(seedRef, creatorHash, holder.getScriptHash());
        String policyId = HexUtil.encodeHexString(policy.getScriptHash());
        var reader = StateProbeFixtures.reader(policy.getScriptHash(), holder.getScriptHash(), creatorHash);
        var datum = PlutusDataAdapter.toClientLib(StateProbeFixtures.state(creatorHash));
        Transaction mint = authorized(new Tx().collectFrom(List.of(seed))
                .mintAsset(policy, new Asset("", BigInteger.ONE), BigIntPlutusData.of(0))
                .payToContract(holderAddress, List.of(Amount.ada(4), Amount.asset(policyId, "", 1)), datum)
                .payToContract(holderAddress, Amount.ada(4), datum)
                .from(creator.baseAddress()), creatorHash).buildAndSign();
        String mintId = submit(mint);
        List<Utxo> states = awaitUtxos(holderAddress, 2).stream().filter(u -> u.getTxHash().equals(mintId)).toList();
        assertEquals(2, states.size());
        var genuine = states.stream().filter(u -> u.getAmount().stream().anyMatch(a -> a.getUnit().equals(policyId))).findFirst().orElseThrow();
        var fake = states.stream().filter(u -> !u.equals(genuine)).findFirst().orElseThrow();
        String reward = AddressProvider.getRewardAddress(reader, NETWORK).toBech32();
        var registration = builder.compose(new Tx().registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).complete();
        assertTrue(registration.isSuccessful(), registration.toString());
        confirm(registration.getValue());
        Transaction read = read(reader, reward, genuine, creatorHash).withTxEvaluator(new JulcTransactionEvaluator(
                new DefaultUtxoSupplier(backend.getUtxoService()), new DefaultProtocolParamsSupplier(backend.getEpochService()), null)).buildAndSign();
        assertFalse(read.getBody().getCollateral().isEmpty());
        var backendReadEvaluation = backend.getTransactionService().evaluateTx(read.serialize());
        assertTrue(backendReadEvaluation.isSuccessful(), "Final balanced reference transaction must also pass the backend evaluator: " + backendReadEvaluation);
        String readId = submit(read);
        String fakeFailure = reject(read(reader, reward, fake, creatorHash), RedeemerTag.Reward);
        var unspent = backend.getUtxoService().getUtxos(holderAddress, 100, 1, OrderEnum.desc);
        assertTrue(unspent.isSuccessful());
        assertTrue(unspent.getValue().stream().anyMatch(u -> u.getTxHash().equals(genuine.getTxHash()) && u.getOutputIndex() == genuine.getOutputIndex()));
        var freshInput = awaitUtxos(creator.baseAddress(), 1).getFirst();
        assertNotEquals(seed.getTxHash(), freshInput.getTxHash());
        String remintFailure = reject(authorized(new Tx().collectFrom(List.of(freshInput))
                .mintAsset(policy, new Asset("", BigInteger.ONE), BigIntPlutusData.of(0))
                .payToContract(holderAddress, List.of(Amount.ada(4), Amount.asset(policyId, "", 1)), datum)
                .from(creator.baseAddress()), creatorHash), RedeemerTag.Mint);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Disposable sealed genesis state probe, not the Kavach account ABI");
        evidence.put("julcVersion", System.getProperty("kavach.julcVersion"));
        evidence.put("cardanoClientLibVersion", System.getProperty("kavach.cclVersion"));
        evidence.put("protocolParameters", parameters.getValue());
        evidence.put("creatorPaymentKeyHash", HexUtil.encodeHexString(creatorHash));
        evidence.put("seed", seed.getTxHash() + "#" + seed.getOutputIndex());
        evidence.put("domain", HexUtil.encodeHexString(StateProbeFixtures.DOMAIN));
        evidence.put("stateDatumCbor", datum.serializeToHex());
        evidence.put("holderHash", HexUtil.encodeHexString(holder.getScriptHash()));
        evidence.put("mintPolicyId", policyId);
        evidence.put("readerHash", HexUtil.encodeHexString(reader.getScriptHash()));
        evidence.put("holderCbor", holder.getCborHex());
        evidence.put("mintPolicyCbor", policy.getCborHex());
        evidence.put("readerCbor", reader.getCborHex());
        evidence.put("mintTx", mintId);
        evidence.put("registrationTx", registration.getValue());
        evidence.put("readTx", readId);
        evidence.put("referenceBudgetEvaluator", "JuLC full transaction evaluator; validity independently confirmed by cardano-node");
        evidence.put("backendReferenceEvaluation", backendReadEvaluation.toString());
        evidence.put("mintRedeemers", mint.getWitnessSet().getRedeemers());
        evidence.put("readRedeemers", read.getWitnessSet().getRedeemers());
        evidence.put("mintTransactionBytes", mint.serialize().length);
        evidence.put("readTransactionBytes", read.serialize().length);
        evidence.put("fakeReferenceNodeRejection", fakeFailure);
        evidence.put("remintNodeRejection", remintFailure);
        var sources = new LinkedHashMap<String, String>();
        for (String name : List.of("SealedStateProbe", "StateNftMintProbe", "StateReferenceProbe")) {
            sources.put(name, sha256(Path.of("src/main/java/com/bloxbean/cardano/kavach/phase0/" + name + ".java")));
        }
        evidence.put("contractSourceSha256", sources);
        evidence.put("toolchainManifestSha256", sha256(Path.of("toolchain/julc/manifest.json")));
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/state-identity-evidence.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("State mint confirmed: " + mintId + "; authenticated reference confirmed: " + readId);
    }

    private String sha256(Path path) throws Exception {
        return HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private QuickTxBuilder.TxContext authorized(Tx tx, byte[] creatorHash) {
        return builder.compose(tx).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withRequiredSigners(creatorHash).withSigner(SignerProviders.signerFrom(creator))
                .withSigner(SignerProviders.signerFrom(sponsor));
    }

    private QuickTxBuilder.TxContext read(PlutusV3Script reader, String reward, Utxo ref, byte[] creatorHash) {
        return authorized(new Tx().readFrom(ref).attachRewardValidator(reader)
                .withdraw(reward, BigInteger.ZERO, BigIntPlutusData.of(0)).from(sponsor.baseAddress()), creatorHash);
    }

    private String submit(Transaction tx) throws Exception {
        var result = backend.getTransactionService().submitTransaction(tx.serialize());
        assertTrue(result.isSuccessful(), result.toString());
        confirm(result.getValue());
        return result.getValue();
    }

    @SuppressWarnings("unchecked") // CCL Result.success/withValue return raw Result.
    private String reject(QuickTxBuilder.TxContext context, RedeemerTag tag) throws Exception {
        // Supply an explicit generous test budget so the node, not the backend's
        // failing reference-input evaluator, decides script validity. Keep isValid=true:
        // a failing script must reject the transaction, not accept collateral loss.
        Transaction tx = context.withTxEvaluator((cbor, inputs) ->
                Result.<List<EvaluationResult>>success("Explicit adversarial test budget").withValue(List.of(
                        new EvaluationResult(tag, 0, new ExUnits(BigInteger.valueOf(2_000_000), BigInteger.valueOf(500_000_000))))))
                .buildAndSign();
        assertTrue(tx.isValid());
        var result = backend.getTransactionService().submitTransaction(tx.serialize());
        assertFalse(result.isSuccessful(), "Invalid script transaction was accepted");
        assertTrue(result.toString().contains("ValidationTagMismatch"), result.toString());
        assertTrue(result.toString().contains("Caused by: error"), "Must fail explicitly, not exhaust its budget");
        return result.toString();
    }

    private void topUp(String address, long ada) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:10000/local-cluster/api/addresses/topup"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"address\":\"" + address
                        + "\",\"adaAmount\":" + ada + "}")).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode(), "Local faucet failed");
            assertFalse(result.body().contains("\"status\":false"), "Local faucet rejected topup");
        }
    }

    private List<Utxo> awaitUtxos(String address, int minimum) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            var result = backend.getUtxoService().getUtxos(address, 100, 1, OrderEnum.desc);
            if (result.isSuccessful() && result.getValue().size() >= minimum) return result.getValue();
            Thread.sleep(1000);
        }
        throw new AssertionError("DevKit did not index funded UTxOs within 30s");
    }

    private void confirm(String txId) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            var result = backend.getTransactionService().getTransaction(txId);
            if (result.isSuccessful() && result.getValue() != null) return;
            Thread.sleep(1000);
        }
        throw new AssertionError("DevKit did not confirm transaction within 30s: " + txId);
    }
}
