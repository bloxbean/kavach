package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;

import java.util.ArrayList;
import java.util.Set;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.eval.SlotConfig;
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


@Tag("devkit")
@Timeout(180)
class ValidityWindowDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);


    @Test
    void ledgerUsesPosixMillisecondsAndExclusiveUpperBound() throws Exception {
        topUp(sponsor.baseAddress(), 100);
        topUp(sponsor.baseAddress(), 10);
        awaitUtxos(2);
        byte[] authority = sponsor.hdKeyPair().getPublicKey().getKeyHash();
        var script = JulcScriptLoader.load(ValidityWindowProbe.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(authority)));
        String reward = AddressProvider.getRewardAddress(script, NETWORK).toBech32();
        String registration = submit(builder.compose(new Tx().registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        var latest = backend.getBlockService().getLatestBlock();
        assertTrue(latest.isSuccessful());
        var block = latest.getValue();
        var previous = backend.getBlockService().getBlockByHash(block.getPreviousBlock());
        assertTrue(previous.isSuccessful());
        long slotDelta = block.getSlot() - previous.getValue().getSlot();
        assertTrue(slotDelta > 0);
        assertEquals(slotDelta, block.getTime() - previous.getValue().getTime(), "This DevKit profile requires one-second slots");
        var slots = new SlotConfig(block.getSlot(), Math.multiplyExact(block.getTime(), 1000), 1000);
        long from = block.getSlot();
        long until = Math.addExact(from, 120);
        var window = PlutusData.constr(0, PlutusData.integer(slots.slotToPosixMs(from)), PlutusData.integer(slots.slotToPosixMs(until)), PlutusData.integer(slots.slotToPosixMs(from)));
        var futureDeadline = PlutusData.constr(0, PlutusData.integer(slots.slotToPosixMs(from)), PlutusData.integer(slots.slotToPosixMs(until)), PlutusData.integer(slots.slotToPosixMs(from) + 1));
        var bad = context(script, reward, futureDeadline, authority, from, until).withTxEvaluator((cbor, inputs) -> budget()).buildAndSign();
        var rejection = backend.getTransactionService().submitTransaction(bad.serialize());
        assertFalse(rejection.isSuccessful());
        assertTrue(rejection.toString().contains("ValidationTagMismatch") && rejection.toString().contains("Caused by: error"), rejection.toString());
        var utxos = new DefaultUtxoSupplier(backend.getUtxoService());
        var parameters = new DefaultProtocolParamsSupplier(backend.getEpochService());
        var configured = new JulcTransactionEvaluator(utxos, parameters, null, slots);
        var tx = context(script, reward, window, authority, from, until).withTxEvaluator(configured).buildAndSign();
        var noConversion = new JulcTransactionEvaluator(utxos, parameters, null).evaluateTx(tx.serialize(), Set.of());
        assertFalse(noConversion.isSuccessful(), "Treating slots as milliseconds must fail this preflight");
        var backendEvaluation = backend.getTransactionService().evaluateTx(tx.serialize());
        assertTrue(backendEvaluation.isSuccessful(), backendEvaluation.toString());
        String withdrawal = submit(tx);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Validity containment and deadline primitive; not a recovery state transition");
        evidence.put("registrationTx", registration);
        evidence.put("withdrawalTx", withdrawal);
        evidence.put("anchorSlot", block.getSlot());
        evidence.put("anchorPosixMillis", slots.zeroSlotPosixMs());
        evidence.put("slotLengthMillis", 1000);
        evidence.put("validFromSlot", from);
        evidence.put("validToExclusiveSlot", until);
        evidence.put("oneMillisecondFutureDeadlineFailure", rejection.toString());
        evidence.put("missingSlotConversionFailure", noConversion.toString());
        evidence.put("redeemers", tx.getWitnessSet().getRedeemers());
        evidence.put("protocolParameters", backend.getEpochService().getProtocolParameters().getValue());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/validity-window-evidence.json"), JsonUtil.getPrettyJson(evidence));
    }

    private QuickTxBuilder.TxContext context(PlutusV3Script script, String reward, PlutusData window, byte[] authority, long from, long until) {
        return builder.compose(new Tx().attachRewardValidator(script).withdraw(reward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(window)).from(sponsor.baseAddress()))
                .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress()).withRequiredSigners(authority)
                .withSigner(SignerProviders.signerFrom(sponsor)).validFrom(from).validTo(until);
    }

    @SuppressWarnings("unchecked")
    private Result<List<EvaluationResult>> budget() {
        return Result.success("Explicit adversarial budget").withValue(List.of(new EvaluationResult(RedeemerTag.Reward, 0,
                new ExUnits(BigInteger.valueOf(1_000_000), BigInteger.valueOf(200_000_000)))));
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
