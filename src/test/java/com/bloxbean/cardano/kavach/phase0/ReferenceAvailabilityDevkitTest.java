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
class ReferenceAvailabilityDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account inputOwner = new Account(NETWORK);

    @Test
    void redundantLockedReferencesAndFullWitnessFallback() throws Exception {
        topUp(sponsor.baseAddress(), 100);
        topUp(sponsor.baseAddress(), 10);
        topUp(inputOwner.baseAddress(), 20);
        awaitUtxos(2);
        var key = ProbeFixtures.keyPair();
        var script = ProbeFixtures.script(key);
        String reward = AddressProvider.getRewardAddress(script, NETWORK).toBech32();
        String holder = AddressProvider.getEntAddress(StateProbeFixtures.holder(), NETWORK).toBech32();
        String publication = submit(builder.compose(new Tx().payToAddress(holder, Amount.ada(5), script)
                        .payToAddress(holder, Amount.ada(5), script).registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        var refsResult = backend.getUtxoService().getUtxos(holder, 100, 1, OrderEnum.desc);
        assertTrue(refsResult.isSuccessful());
        var refs = refsResult.getValue().stream().filter(u -> u.getTxHash().equals(publication)).toList();
        assertEquals(2, refs.size());
        var runs = new ArrayList<Object>();
        for (int mode = 0; mode < 3; mode++) {
            var inputs = backend.getUtxoService().getUtxos(inputOwner.baseAddress(), 100, 1, OrderEnum.desc);
            assertTrue(inputs.isSuccessful());
            var input = inputs.getValue().getFirst();
            var ref = new TxOutRef(new TxId(HexUtil.decodeHexString(input.getTxHash())), BigInteger.valueOf(input.getOutputIndex()));
            var auth = ProbeFixtures.authorize(key, ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref));
            var withdrawal = new Tx().attachRewardValidator(script).withdraw(reward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(auth));
            if (mode < 2) withdrawal.readFrom(refs.get(mode));
            var context = builder.compose(withdrawal,
                            new Tx().collectFrom(List.of(input)).payToAddress(inputOwner.baseAddress(), Amount.ada(20)).from(inputOwner.baseAddress()))
                    .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                    .withSigner(SignerProviders.signerFrom(sponsor)).withSigner(SignerProviders.signerFrom(inputOwner))
                    .withTxEvaluator(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                            new DefaultProtocolParamsSupplier(backend.getEpochService()), new DefaultScriptSupplier(backend.getScriptService())));
            if (mode < 2) context.withReferenceScripts(script).removeDuplicateScriptWitnesses(true);
            var tx = context.buildAndSign();
            if (mode < 2)
                assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(), "Reference path must omit full script witness");
            else assertEquals(1, tx.getWitnessSet().getPlutusV3Scripts().size());
            String txId = submit(tx);
            var run = new LinkedHashMap<String, Object>();
            run.put("mode", mode == 0 ? "primary-reference" : mode == 1 ? "secondary-reference" : "full-witness-fallback");
            run.put("tx", txId);
            run.put("transactionBytes", tx.serialize().length);
            run.put("redeemers", tx.getWitnessSet().getRedeemers());
            runs.add(run);
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Same compiled withdrawal hash via two always-fails-locked references and an independent full witness");
        evidence.put("publicationTx", publication);
        evidence.put("scriptHash", HexUtil.encodeHexString(script.getScriptHash()));
        evidence.put("scriptCbor", script.getCborHex());
        evidence.put("runs", runs);
        evidence.put("protocolParameters", backend.getEpochService().getProtocolParameters().getValue());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/reference-availability-evidence.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Reference copies and full-witness fallback all confirmed for " + HexUtil.encodeHexString(script.getScriptHash()));
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
