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
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.util.Optional;

import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;

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


@Tag("devkit")
@Timeout(240)
class BindingCheckpointDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account creator = new Account(NETWORK);

    @Test
    void pairedRewardingExecutionAndMismatchedEvidence() throws Exception {
        var params = backend.getEpochService().getProtocolParameters();
        assertTrue(params.isSuccessful());
        assertEquals(11, params.getValue().getProtocolMajorVer());
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
        var key = ProbeFixtures.keyPair();
        var sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(creatorHash)), Optional.empty());
        var pair = BindingFixtures.pair(key, policy.getScriptHash(), sink, holder.getScriptHash(), creatorHash);
        String moduleReward = AddressProvider.getRewardAddress(pair.module(), NETWORK).toBech32();
        String coreReward = AddressProvider.getRewardAddress(pair.core(), NETWORK).toBech32();
        var mint = builder.compose(new Tx().collectFrom(List.of(seed))
                        .mintAsset(policy, new Asset("", BigInteger.ONE), BigIntPlutusData.of(0))
                        .payToContract(holderAddress, List.of(Amount.ada(4), Amount.asset(policyId, "", 1)), PlutusDataAdapter.toClientLib(StateProbeFixtures.state(creatorHash)))
                        .from(creator.baseAddress())).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withRequiredSigners(creatorHash).withSigner(SignerProviders.signerFrom(creator)).withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign();
        String mintId = submit(mint);
        var state = awaitUtxos(holderAddress, 1).stream().filter(u -> u.getTxHash().equals(mintId)).findFirst().orElseThrow();
        String registrationId = submit(builder.compose(new Tx().registerStakeAddress(moduleReward).registerStakeAddress(coreReward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        var input = awaitUtxos(creator.baseAddress(), 1).getFirst();
        var inputRef = new TxOutRef(new TxId(HexUtil.decodeHexString(input.getTxHash())), BigInteger.valueOf(input.getOutputIndex()));
        var envelope = BindingFixtures.envelope(policy.getScriptHash(), inputRef, pair);
        var auth = BindingFixtures.authorization(key, envelope, -1, -1);
        var wrong = BindingFixtures.authorization(ProbeFixtures.keyPair(), envelope, -1, -1);
        var invalid = context(pair, coreReward, moduleReward, state, input, wrong, wrong)
                .withTxEvaluator((cbor, inputs) -> budget()).buildAndSign();
        assertTrue(invalid.isValid());
        var invalidResult = backend.getTransactionService().submitTransaction(invalid.serialize());
        assertFalse(invalidResult.isSuccessful());
        assertTrue(invalidResult.toString().contains("ValidationTagMismatch"), invalidResult.toString());
        assertTrue(invalidResult.toString().contains("Caused by: error"), invalidResult.toString());
        var mismatch = context(pair, coreReward, moduleReward, state, input, auth, wrong)
                .withTxEvaluator((cbor, inputs) -> budget()).buildAndSign();
        var mismatchResult = backend.getTransactionService().submitTransaction(mismatch.serialize());
        assertFalse(mismatchResult.isSuccessful());
        assertTrue(mismatchResult.toString().contains("ValidationTagMismatch"), mismatchResult.toString());
        var valid = context(pair, coreReward, moduleReward, state, input, auth, auth).buildAndSign();
        var remoteEvaluation = backend.getTransactionService().evaluateTx(valid.serialize());
        assertTrue(remoteEvaluation.isSuccessful(), remoteEvaluation.toString());
        assertEquals(2, valid.getWitnessSet().getRedeemers().size());
        String validId = submit(valid);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Paired core/module genesis-state authorization; no production account spend semantics");
        evidence.put("mintTx", mintId);
        evidence.put("registrationTx", registrationId);
        evidence.put("pairedWithdrawalTx", validId);
        evidence.put("coreHash", HexUtil.encodeHexString(pair.core().getScriptHash()));
        evidence.put("moduleHash", HexUtil.encodeHexString(pair.module().getScriptHash()));
        evidence.put("coreCbor", pair.core().getCborHex());
        evidence.put("moduleCbor", pair.module().getCborHex());
        evidence.put("policy", policyId);
        evidence.put("publicKey", HexUtil.encodeHexString(ProbeFixtures.publicKey(key)));
        evidence.put("authorizationCbor", PlutusDataAdapter.toClientLib(auth).serializeToHex());
        evidence.put("redeemers", valid.getWitnessSet().getRedeemers());
        evidence.put("transactionBytes", valid.serialize().length);
        evidence.put("protocolParameters", params.getValue());
        evidence.put("remoteEvaluation", remoteEvaluation.toString());
        evidence.put("invalidSignatureRejection", invalidResult.toString());
        evidence.put("mismatchedRedeemerRejection", mismatchResult.toString());
        evidence.put("sourceSha256", HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                Path.of("src/main/java/com/bloxbean/cardano/kavach/phase0/BindingCheckpointProbe.java")))));
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/binding-evidence.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Paired withdrawal confirmed: " + validId + "; bytes=" + valid.serialize().length);
    }

    @SuppressWarnings("unchecked")
    private Result<List<EvaluationResult>> budget() {
        return Result.success("Explicit adversarial budget").withValue(List.of(
                new EvaluationResult(RedeemerTag.Reward, 0, new ExUnits(BigInteger.valueOf(4_000_000), BigInteger.valueOf(1_000_000_000))),
                new EvaluationResult(RedeemerTag.Reward, 1, new ExUnits(BigInteger.valueOf(4_000_000), BigInteger.valueOf(1_000_000_000)))));
    }

    private QuickTxBuilder.TxContext context(BindingFixtures.Pair pair, String coreReward, String moduleReward, Utxo state, Utxo input,
                                             PlutusData coreAuth, PlutusData moduleAuth) {
        return builder.compose(new Tx().readFrom(state).attachRewardValidator(pair.core()).attachRewardValidator(pair.module())
                                .withdraw(coreReward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(coreAuth))
                                .withdraw(moduleReward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(moduleAuth)),
                        new Tx().collectFrom(List.of(input)).payToAddress(creator.baseAddress(), Amount.ada(16)).from(creator.baseAddress()))
                .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withSigner(SignerProviders.signerFrom(sponsor)).withSigner(SignerProviders.signerFrom(creator))
                .withTxEvaluator((cbor, inputs) -> {
                    var estimated = new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                            new DefaultProtocolParamsSupplier(backend.getEpochService()), null).evaluateTx(cbor, inputs);
                    assertTrue(estimated.isSuccessful(), estimated.toString());
                    // Both scripts have measured maxima below this ceiling. Provisional estimates
                    // can underbudget the final balanced context; node validation remains mandatory.
                    return budget();
                });
    }

    private String submit(Transaction tx) throws Exception {
        var result = backend.getTransactionService().submitTransaction(tx.serialize());
        assertTrue(result.isSuccessful(), result.toString());
        confirm(result.getValue());
        return result.getValue();
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
