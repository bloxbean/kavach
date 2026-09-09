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
import java.util.Comparator;
import java.util.Optional;

import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
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
class WholeUtxoTransferDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account recipient = new Account(NETWORK);

    @Test
    void oversizedDepositCanMoveWholeWithSponsorTopup() throws Exception {
        topUp(sponsor.baseAddress(), 500);
        topUp(sponsor.baseAddress(), 10);
        awaitUtxos(2);
        byte[] authority = sponsor.hdKeyPair().getPublicKey().getKeyHash();
        var holder = JulcScriptLoader.load(OwnedAssetFixture.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(authority)));
        var account = new Address(new Credential.ScriptCredential(new ScriptHash(holder.getScriptHash())), Optional.empty());
        var destination = new Address(new Credential.PubKeyCredential(new PubKeyHash(recipient.hdKeyPair().getPublicKey().getKeyHash())), Optional.empty());
        String holderAddress = AddressProvider.getEntAddress(holder, NETWORK).toBech32();
        var checkpoint = JulcScriptLoader.load(WholeUtxoTransferProbe.class, PlutusDataAdapter.toClientLib(account.toPlutusData()));
        String reward = AddressProvider.getRewardAddress(checkpoint, NETWORK).toBech32();
        var policy = new ScriptPubkey(HexUtil.encodeHexString(authority));
        var assets = new ArrayList<Asset>();
        for (int i = 0; i < 140; i++) {
            byte[] name = new byte[32];
            name[0] = (byte) i;
            assets.add(new Asset("0x" + HexUtil.encodeHexString(name), BigInteger.ONE));
        }
        String mintId = submit(builder.compose(new Tx().mintAssets(policy, assets, holderAddress).registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        var found = backend.getUtxoService().getUtxos(holderAddress, 100, 1, OrderEnum.desc);
        assertTrue(found.isSuccessful());
        var input = found.getValue().stream().filter(u -> u.getTxHash().equals(mintId)).findFirst().orElseThrow();
        assertEquals(141, input.getAmount().size(), "140 tokens must remain together in one deposit");
        var ada = input.getAmount().stream().filter(a -> a.getUnit().equals("lovelace")).findFirst().orElseThrow().getQuantity();
        Value incoming = Value.lovelace(ada);
        var ordered = input.getAmount().stream().filter(a -> !a.getUnit().equals("lovelace")).sorted(Comparator.comparing(Amount::getUnit).reversed()).toList();
        for (var amount : ordered)
            incoming = incoming.merge(Value.singleton(new PolicyId(HexUtil.decodeHexString(amount.getUnit().substring(0, 56))),
                    new TokenName(HexUtil.decodeHexString(amount.getUnit().substring(56))), amount.getQuantity()));
        var ref = new TxOutRef(new TxId(HexUtil.decodeHexString(input.getTxHash())), BigInteger.valueOf(input.getOutputIndex()));
        var transfer = PlutusData.constr(0, ref.toPlutusData(), PlutusData.integer(0), destination.toPlutusData(), PlutusData.bytes(WireFormat.ledgerValueDigest(incoming.toPlutusData())));
        var amounts = new ArrayList<Amount>();
        for (var amount : input.getAmount())
            amounts.add(new Amount(amount.getUnit(), amount.getUnit().equals("lovelace") ? amount.getQuantity().add(BigInteger.valueOf(1_000_000)) : amount.getQuantity()));
        var missingToken = new ArrayList<>(amounts);
        missingToken.removeIf(a -> a.getUnit().equals(ordered.getFirst().getUnit()));
        var invalid = spending(holder, checkpoint, reward, input, transfer, missingToken, authority).withTxEvaluator((cbor, utxos) -> budget(cbor)).buildAndSign();
        var rejection = backend.getTransactionService().submitTransaction(invalid.serialize());
        assertFalse(rejection.isSuccessful());
        assertTrue(rejection.toString().contains("ValidationTagMismatch") && rejection.toString().contains("Caused by: error"), rejection.toString());
        var tx = spending(holder, checkpoint, reward, input, transfer, amounts, authority).withTxEvaluator((cbor, utxos) -> budget(cbor)).buildAndSign();
        var evaluated = backend.getTransactionService().evaluateTx(tx.serialize());
        assertTrue(evaluated.isSuccessful(), evaluated.toString());
        String transferId = submit(tx);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Whole-value component with separately key-authorized disposable script holder; not a Kavach account");
        evidence.put("mintTx", mintId);
        evidence.put("transferTx", transferId);
        evidence.put("nativeAssets", 140);
        evidence.put("inputLovelace", ada);
        evidence.put("externalTopupLovelace", 1_000_000);
        evidence.put("inputValueCbor", PlutusDataAdapter.toClientLib(incoming.toPlutusData()).serializeToHex());
        evidence.put("transactionBytes", tx.serialize().length);
        evidence.put("redeemers", tx.getWitnessSet().getRedeemers());
        evidence.put("missingTokenNodeFailure", rejection.toString());
        evidence.put("protocolParameters", backend.getEpochService().getProtocolParameters().getValue());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/whole-utxo-evidence.json"), JsonUtil.getPrettyJson(evidence));
    }

    private QuickTxBuilder.TxContext spending(PlutusV3Script holder, PlutusV3Script checkpoint, String reward, Utxo input, PlutusData transfer, List<Amount> amounts, byte[] authority) {
        return builder.compose(new Tx().attachSpendingValidator(holder).attachRewardValidator(checkpoint).collectFrom(input, BigIntPlutusData.of(0))
                        .payToAddress(recipient.enterpriseAddress(), amounts).withdraw(reward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(transfer)))
                .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress()).withRequiredSigners(authority).withSigner(SignerProviders.signerFrom(sponsor))
                .withTxEvaluator(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()), new DefaultProtocolParamsSupplier(backend.getEpochService()), null));
    }

    @SuppressWarnings("unchecked")
    private Result<List<EvaluationResult>> budget(byte[] cbor) {
        try {
            var tx = Transaction.deserialize(cbor);
            var results = tx.getWitnessSet().getRedeemers().stream().map(r -> new EvaluationResult(r.getTag(), r.getIndex().intValueExact(),
                    new ExUnits(BigInteger.valueOf(2_000_000), BigInteger.valueOf(1_000_000_000)))).toList();
            return Result.success("Explicit adversarial budget").withValue(results);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
