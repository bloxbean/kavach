package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.julc.core.PlutusData;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Only uses disposable keys and the local DevKit faucet. Never reads wallet credentials.
 */
@Tag("devkit")
@Timeout(180)
class WithdrawalProbeDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account inputOwner = new Account(NETWORK);

    @Test
    void compiledWithdrawalOnLedger() throws Exception {
        var parameters = backend.getEpochService().getProtocolParameters();
        assertTrue(parameters.isSuccessful(), "DevKit protocol parameters unavailable: " + parameters);
        assertEquals(11, parameters.getValue().getProtocolMajorVer(), "Re-review experiment for a different protocol version");
        topUp(sponsor.baseAddress(), 100);
        topUp(sponsor.baseAddress(), 10);
        topUp(inputOwner.baseAddress(), 20);
        awaitUtxos(2);

        var key = ProbeFixtures.keyPair();
        var script = ProbeFixtures.script(key);
        String reward = AddressProvider.getRewardAddress(script, NETWORK).toBech32();

        // Legacy registration intentionally has no script witness. It is not proof that
        // Conway explicit-deposit registration can omit one.
        var registration = builder.compose(new Tx().registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).complete();
        assertTrue(registration.isSuccessful(), "Legacy registration failed: " + registration);
        confirm(registration.getValue());
        System.out.println("Legacy registration confirmed: " + registration.getValue());

        var ownedInputs = backend.getUtxoService().getUtxos(inputOwner.baseAddress(), 100, 1);
        assertTrue(ownedInputs.isSuccessful());
        var input = ownedInputs.getValue().getFirst();
        var ref = new TxOutRef(new TxId(HexUtil.decodeHexString(input.getTxHash())),
                BigInteger.valueOf(input.getOutputIndex()));
        var challenge = ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref);
        var authorization = ProbeFixtures.authorize(key, challenge);

        var wrongSignature = ProbeFixtures.authorize(ProbeFixtures.keyPair(), challenge);
        String signatureFailure = rejectedEvaluation(script, reward, input, wrongSignature);
        assertTrue(signatureFailure.contains("Error evaluated"), signatureFailure);
        System.out.println("Wrong-key authorization rejected by DevKit evaluator: " + signatureFailure);

        byte[] identity = new byte[32];
        identity[0] = 1;
        byte[] identityDomain = ProbeFixtures.publicKey(key); // fresh public domain avoids duplicate registration on reruns
        var identityScript = JulcScriptLoader.load(WithdrawalProbe.class,
                PlutusDataAdapter.toClientLib(PlutusData.bytes(identity)), PlutusDataAdapter.toClientLib(PlutusData.bytes(identityDomain)));
        String identityReward = AddressProvider.getRewardAddress(identityScript, NETWORK).toBech32();
        var identityRegistration = builder.compose(new Tx().registerStakeAddress(identityReward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).complete();
        assertTrue(identityRegistration.isSuccessful(), identityRegistration.toString());
        confirm(identityRegistration.getValue());
        byte[] universalForgery = new byte[64];
        universalForgery[0] = 1; // R = identity, S = zero
        var forged = PlutusData.constr(0, ProbeFixtures.challenge(identityDomain, ref), PlutusData.bytes(universalForgery));
        var forgedTx = context(identityScript, identityReward, input, forged).withTxEvaluator((cbor, utxos) -> adversarialBudget()).buildAndSign();
        var identityFailure = backend.getTransactionService().submitTransaction(forgedTx.serialize());
        assertFalse(identityFailure.isSuccessful(), "Identity-key forgery must fail at the node");
        assertTrue(identityFailure.toString().contains("ValidationTagMismatch") && identityFailure.toString().contains("Caused by: error"), identityFailure.toString());

        Transaction transaction = context(script, reward, input, authorization).buildAndSign();
        assertEquals(1, transaction.getBody().getWithdrawals().size());
        assertEquals(1, transaction.getWitnessSet().getRedeemers().size());
        assertEquals(RedeemerTag.Reward, transaction.getWitnessSet().getRedeemers().getFirst().getTag());
        assertFalse(transaction.getBody().getCollateral().isEmpty(), "Script execution needs key-controlled collateral");
        var submitted = backend.getTransactionService().submitTransaction(transaction.serialize());
        assertTrue(submitted.isSuccessful(), "Compiled transaction rejected: " + submitted);
        confirm(submitted.getValue());
        System.out.println("Compiled withdrawal confirmed: " + submitted.getValue());
        System.out.println("Compiled script hash: " + HexUtil.encodeHexString(script.getScriptHash()));
        System.out.println("Ledger execution units: " + transaction.getWitnessSet().getRedeemers());

        var remaining = backend.getUtxoService().getUtxos(inputOwner.baseAddress(), 100, 1);
        assertTrue(remaining.isSuccessful(), "Cannot check consumption");
        assertTrue(remaining.getValue().stream().noneMatch(u -> u.getTxHash().equals(input.getTxHash())
                && u.getOutputIndex() == input.getOutputIndex()), "Signed challenge input must be consumed");
        var replay = backend.getTransactionService().submitTransaction(transaction.serialize());
        assertFalse(replay.isSuccessful(), "Already consumed transaction was accepted again");
        assertTrue(replay.toString().contains("BadInputsUTxO") || replay.toString().contains("All inputs are spent"),
                "Expected spent-input rejection: " + replay);
        System.out.println("Replay rejected because inputs are spent");

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Phase 0 probe; source-fixed compiler output, no artifact rewriting");
        evidence.put("julcVersion", System.getProperty("kavach.julcVersion"));
        evidence.put("cardanoClientLibVersion", System.getProperty("kavach.cclVersion"));
        evidence.put("toolchainManifestSha256", HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("toolchain/julc/manifest.json")))));
        evidence.put("contractSourceSha256", HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("src/main/java/com/bloxbean/cardano/kavach/phase0/WithdrawalProbe.java")))));
        evidence.put("protocolParameters", parameters.getValue());
        evidence.put("publicKey", HexUtil.encodeHexString(ProbeFixtures.publicKey(key)));
        evidence.put("deploymentDomain", HexUtil.encodeHexString(ProbeFixtures.DOMAIN));
        evidence.put("compiledScriptCbor", script.getCborHex());
        evidence.put("compiledScriptHash", HexUtil.encodeHexString(script.getScriptHash()));
        evidence.put("registrationTx", registration.getValue());
        evidence.put("withdrawalTx", submitted.getValue());
        evidence.put("executionUnits", transaction.getWitnessSet().getRedeemers().getFirst().getExUnits());
        evidence.put("transactionBytes", transaction.serialize().length);
        evidence.put("wrongSignatureEvaluationFailure", signatureFailure);
        evidence.put("identityKeyRegistrationTx", identityRegistration.getValue());
        evidence.put("identityKeyForgeryNodeFailure", identityFailure.toString());
        evidence.put("replayFailure", replay.toString());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/devkit-evidence.json"), JsonUtil.getPrettyJson(evidence));
    }

    @SuppressWarnings("unchecked")
    private Result<List<EvaluationResult>> adversarialBudget() {
        return Result.success("Explicit forgery-rejection budget").withValue(List.of(new EvaluationResult(RedeemerTag.Reward, 0,
                new ExUnits(BigInteger.valueOf(1_000_000), BigInteger.valueOf(200_000_000)))));
    }

    private QuickTxBuilder.TxContext context(PlutusV3Script script, String reward,
                                             Utxo input, PlutusData authorization) {
        return builder.compose(new Tx().attachRewardValidator(script)
                                .withdraw(reward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(authorization)),
                        new Tx().collectFrom(List.of(input)).payToAddress(inputOwner.baseAddress(), Amount.ada(20))
                                .from(inputOwner.baseAddress()))
                .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withSigner(SignerProviders.signerFrom(sponsor))
                .withSigner(SignerProviders.signerFrom(inputOwner));
    }

    private String rejectedEvaluation(PlutusV3Script script, String reward, Utxo input,
                                      PlutusData authorization) {
        var failure = new AtomicReference<String>();
        try {
            var transaction = context(script, reward, input, authorization)
                    .withTxEvaluator((cbor, inputs) -> {
                        var result = backend.getTransactionService().evaluateTx(cbor);
                        if (!result.isSuccessful()) failure.set(result.toString());
                        return result;
                    }).buildAndSign();
            var evaluation = backend.getTransactionService().evaluateTx(transaction.serialize());
            if (!evaluation.isSuccessful()) failure.set(evaluation.toString());
        } catch (RuntimeException exception) {
            if (failure.get() == null) throw exception;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertNotNull(failure.get(), "Must fail at the node evaluator, not in setup or transaction construction");
        return failure.get();
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

    private List<Utxo> awaitUtxos(int minimum) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            var result = backend.getUtxoService().getUtxos(sponsor.baseAddress(), 100, 1);
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
