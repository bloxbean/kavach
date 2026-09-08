package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh local-devnet identities only. No existing cluster credentials or ledger reset. */
@Tag("devkit")
@Timeout(180)
class RewardLifecycleProbeDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account authority = new Account(NETWORK);

    private PlutusV3Script script(byte[] authorityHash, BigInteger deposit) {
        var sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(authorityHash)), Optional.empty());
        return JulcScriptLoader.load(RewardLifecycleProbe.class,
                PlutusDataAdapter.toClientLib(PlutusData.bytes(authorityHash)),
                PlutusDataAdapter.toClientLib(sink.toPlutusData()),
                PlutusDataAdapter.toClientLib(PlutusData.integer(deposit)));
    }

    @Test void explicitRegistrationAndDeregistrationProtection() throws Exception {
        var params = backend.getEpochService().getProtocolParameters();
        assertTrue(params.isSuccessful());
        assertEquals(11, params.getValue().getProtocolMajorVer());
        var deposit = new BigInteger(params.getValue().getKeyDeposit());
        topUp(sponsor.baseAddress(), 100);
        topUp(sponsor.baseAddress(), 10);
        awaitUtxos(sponsor.baseAddress(), 2);
        byte[] authorityHash = authority.hdKeyPair().getPublicKey().getKeyHash();
        var script = script(authorityHash, deposit);
        String reward = AddressProvider.getRewardAddress(script, NETWORK).toBech32();
        // CCL's high-level registration builds the legacy certificate. Replace it
        // before balancing with its deposit-bearing form and add the cert redeemer.
        Transaction registration = funded(new ExplicitRegistrationTx().registerStakeAddress(reward).attachCertificateValidator(script)
                .from(sponsor.baseAddress())).preBalanceTx((context, transaction) -> {
                    var legacy = (StakeRegistration) transaction.getBody().getCerts().getFirst();
                    transaction.getBody().getCerts().set(0, new RegCert(legacy.getStakeCredential(), deposit));
                    transaction.getWitnessSet().setRedeemers(new ArrayList<>(List.of(new Redeemer(
                            RedeemerTag.Cert, BigInteger.ZERO, BigIntPlutusData.of(-1),
                            new ExUnits(BigInteger.ZERO, BigInteger.ZERO)))));
                }).buildAndSign();
        assertTrue(registration.getBody().getRequiredSigners() == null || registration.getBody().getRequiredSigners().isEmpty());
        assertInstanceOf(RegCert.class, registration.getBody().getCerts().getFirst());
        assertFalse(registration.getBody().getCollateral().isEmpty());
        var missingWitness = Transaction.deserialize(registration.serialize());
        missingWitness.getWitnessSet().getPlutusV3Scripts().clear();
        var missingWitnessResult = backend.getTransactionService().submitTransaction(missingWitness.serialize());
        assertFalse(missingWitnessResult.isSuccessful());
        assertTrue(missingWitnessResult.toString().contains("MissingScriptWitnesses"), missingWitnessResult.toString());
        String registrationId = submit(registration);
        Transaction withdrawal = funded(new Tx().attachRewardValidator(script)
                .withdraw(reward, BigInteger.ZERO, BigIntPlutusData.of(-1)).from(sponsor.baseAddress()))
                .withRequiredSigners(authorityHash).withSigner(SignerProviders.signerFrom(authority)).buildAndSign();
        String withdrawalId = submit(withdrawal);
        Transaction deregistration = fixedBudget(new Tx().attachCertificateValidator(script)
                .deregisterStakeAddress(reward, BigIntPlutusData.of(-1), sponsor.baseAddress()).from(sponsor.baseAddress()), RedeemerTag.Cert)
                .buildAndSign();
        assertTrue(deregistration.isValid());
        var deregistrationResult = backend.getTransactionService().submitTransaction(deregistration.serialize());
        assertFalse(deregistrationResult.isSuccessful());
        assertTrue(deregistrationResult.toString().contains("ValidationTagMismatch"), deregistrationResult.toString());
        assertTrue(deregistrationResult.toString().contains("Caused by: error"), deregistrationResult.toString());
        // A second legacy registration has no script witness; the ledger must reject it.
        Transaction duplicate = builder.compose(new Tx().registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign();
        var duplicateResult = backend.getTransactionService().submitTransaction(duplicate.serialize());
        assertFalse(duplicateResult.isSuccessful());
        assertTrue(duplicateResult.toString().contains("StakeKeyRegistered"), duplicateResult.toString());
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Explicit-deposit registration, zero withdrawal, deregistration and duplicate-registration rejection; no live positive rewards yet");
        evidence.put("protocolParameters", params.getValue());
        evidence.put("julcVersion", System.getProperty("kavach.julcVersion"));
        evidence.put("cardanoClientLibVersion", System.getProperty("kavach.cclVersion"));
        evidence.put("authorityPaymentKeyHash", HexUtil.encodeHexString(authorityHash));
        evidence.put("scriptCbor", script.getCborHex());
        evidence.put("scriptHash", HexUtil.encodeHexString(script.getScriptHash()));
        evidence.put("registrationTx", registrationId);
        evidence.put("withdrawalTx", withdrawalId);
        evidence.put("registrationRedeemers", registration.getWitnessSet().getRedeemers());
        evidence.put("withdrawalRedeemers", withdrawal.getWitnessSet().getRedeemers());
        evidence.put("missingRegistrationScriptRejection", missingWitnessResult.toString());
        evidence.put("deregistrationRejection", deregistrationResult.toString());
        evidence.put("duplicateRegistrationRejection", duplicateResult.toString());
        evidence.put("sourceSha256", HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("src/main/java/com/bloxbean/cardano/kavach/phase0/RewardLifecycleProbe.java")))));
        evidence.put("toolchainManifestSha256", HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("toolchain/julc/manifest.json")))));
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/reward-lifecycle-evidence.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Explicit registration confirmed: " + registrationId + "; zero withdrawal: " + withdrawalId);
    }

    // Harness adapter: preBalanceTx adds a certificate redeemer that CCL's legacy
    // registration intent cannot advertise. Explicitly enable script infrastructure.
    private static final class ExplicitRegistrationTx extends Tx {
        @Override public boolean hasScriptIntents() { return true; }
    }

    private QuickTxBuilder.TxContext funded(Tx tx) {
        return builder.compose(tx).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withSigner(SignerProviders.signerFrom(sponsor)).withTxEvaluator(new JulcTransactionEvaluator(
                        new DefaultUtxoSupplier(backend.getUtxoService()), new DefaultProtocolParamsSupplier(backend.getEpochService()), null));
    }
    @SuppressWarnings("unchecked")
    private QuickTxBuilder.TxContext fixedBudget(Tx tx, RedeemerTag tag) {
        return funded(tx).withTxEvaluator((cbor, inputs) -> Result.success("Adversarial test budget").withValue(List.of(
                new EvaluationResult(tag, 0, new ExUnits(BigInteger.valueOf(2_000_000), BigInteger.valueOf(500_000_000))))));
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
