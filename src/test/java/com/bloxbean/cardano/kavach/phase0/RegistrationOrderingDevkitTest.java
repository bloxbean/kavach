package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.TxId;
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


@Tag("devkit")
@Timeout(180)
class RegistrationOrderingDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account authority = new Account(NETWORK);

    @Test
    void permissionlessLegacyReregistrationAndSameTransactionOrdering() throws Exception {
        topUp(sponsor.baseAddress(), 100);
        topUp(sponsor.baseAddress(), 10);
        awaitUtxos(sponsor.baseAddress(), 2);
        var params = backend.getEpochService().getProtocolParameters();
        assertTrue(params.isSuccessful());
        assertEquals(11, params.getValue().getProtocolMajorVer());
        byte[] authorityHash = authority.hdKeyPair().getPublicKey().getKeyHash();
        var fixture = JulcScriptLoader.load(DeregistrationFixture.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(authorityHash)));
        String reward = AddressProvider.getRewardAddress(fixture, NETWORK).toBech32();
        String first = submit(legacy(reward).buildAndSign());
        String removed = submit(funded(new Tx().attachCertificateValidator(fixture)
                .deregisterStakeAddress(reward, BigIntPlutusData.of(0), sponsor.baseAddress()).from(sponsor.baseAddress()))
                .withRequiredSigners(authorityHash).withSigner(SignerProviders.signerFrom(authority)).buildAndSign());
        var registration = legacy(reward).buildAndSign();
        assertTrue(registration.getWitnessSet().getRedeemers() == null || registration.getWitnessSet().getRedeemers().isEmpty());
        assertTrue(registration.getBody().getRequiredSigners() == null || registration.getBody().getRequiredSigners().isEmpty());
        String restored = submit(registration);
        // Fresh rewarding-only script: try legacy registration and zero withdrawal together.
        var signatureKey = ProbeFixtures.keyPair();
        var withdrawalScript = ProbeFixtures.script(signatureKey);
        String freshReward = AddressProvider.getRewardAddress(withdrawalScript, NETWORK).toBech32();
        topUp(authority.baseAddress(), 20);
        var source = awaitUtxos(authority.baseAddress(), 1).getFirst();
        var ref = new TxOutRef(new TxId(HexUtil.decodeHexString(source.getTxHash())), BigInteger.valueOf(source.getOutputIndex()));
        var auth = ProbeFixtures.authorize(signatureKey, ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref));
        var combined = builder.compose(new Tx().registerStakeAddress(freshReward).attachRewardValidator(withdrawalScript)
                                .withdraw(freshReward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(auth)).from(sponsor.baseAddress()),
                        new Tx().collectFrom(List.of(source)).payToAddress(authority.baseAddress(), Amount.ada(20)).from(authority.baseAddress()))
                .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withSigner(SignerProviders.signerFrom(sponsor)).withSigner(SignerProviders.signerFrom(authority))
                .withTxEvaluator(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                        new DefaultProtocolParamsSupplier(backend.getEpochService()), null)).buildAndSign();
        var combinedResult = backend.getTransactionService().submitTransaction(combined.serialize());
        // Expected Conway check: withdrawal account must already be registered before this transaction.
        assertFalse(combinedResult.isSuccessful(), "Revisit documented ordering if target ledger changes");
        assertTrue(combinedResult.toString().contains("Withdrawals"), combinedResult.toString());
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Controlled deregistration fixture; not a Kavach production escape path");
        evidence.put("protocolParameters", params.getValue());
        evidence.put("initialRegistrationTx", first);
        evidence.put("authorizedDeregistrationTx", removed);
        evidence.put("permissionlessLegacyReregistrationTx", restored);
        evidence.put("sameTransactionRegistrationWithdrawalRejection", combinedResult.toString());
        evidence.put("fixtureScriptCbor", fixture.getCborHex());
        evidence.put("fixtureAuthority", HexUtil.encodeHexString(authorityHash));
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/registration-ordering-evidence.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Permissionless legacy re-registration confirmed: " + restored);
    }

    private QuickTxBuilder.TxContext legacy(String reward) {
        return builder.compose(new Tx().registerStakeAddress(reward).from(sponsor.baseAddress())).withSigner(SignerProviders.signerFrom(sponsor));
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
