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
import java.util.Set;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;

import static org.junit.jupiter.api.Assertions.*;


@Tag("pairedRewardCredit")
@Timeout(6000)
class PairedPositiveRewardDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    private final Account sponsor = new Account(NETWORK);
    private final Account creator = new Account(NETWORK);

    @Test
    void bothPositiveCheckpointsUseDistinctReceiptsAtTheSharedSink() throws Exception {
        if (Boolean.getBoolean("kavach.resumePairedRewards")) {
            var saved = JsonUtil.parseJson(Files.readString(Path.of("build/phase0/paired-reward-pending.json")));
            var evidence = new LinkedHashMap<String, Object>();
            saved.fields().forEachRemaining(entry -> evidence.put(entry.getKey(), entry.getValue()));
            finish(saved.get("coreReward").asText(), saved.get("moduleReward").asText(),
                    saved.get("amountPerCredential").bigIntegerValue(),
                    Transaction.deserialize(Files.readAllBytes(Path.of("build/phase0/paired-reward-full-withdrawal.cbor"))),
                    Transaction.deserialize(Files.readAllBytes(Path.of("build/phase0/paired-reward-reused-receipt.cbor"))), evidence);
            return;
        }
        var params = backend.getEpochService().getProtocolParameters();
        assertTrue(params.isSuccessful());
        assertEquals(11, params.getValue().getProtocolMajorVer());
        topUp(sponsor.baseAddress(), 9000);
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
        BigInteger deposit = params.getValue().getGovActionDeposit();
        assertTrue(deposit.compareTo(BigInteger.valueOf(1_000_000_000)) <= 0);
        var anchor = new Anchor("https://example.invalid/kavach-paired-reward-fixture", new byte[32]);
        String proposalId = submit(builder.compose(new Tx().createProposal(new InfoAction(), coreReward, anchor)
                        .createProposal(new InfoAction(), moduleReward, anchor).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        var auth = BindingFixtures.authorization(key, envelope, 0, 1);
        var reused = BindingFixtures.authorization(key, envelope, 0, 0);
        var full = context(pair, coreReward, moduleReward, state, input, auth, deposit).buildAndSign();
        var invalid = context(pair, coreReward, moduleReward, state, input, reused, deposit).buildAndSign();
        assertEquals(0, full.getBody().getTtl());
        Files.createDirectories(Path.of("build/phase0"));
        Files.write(Path.of("build/phase0/paired-reward-full-withdrawal.cbor"), full.serialize());
        Files.write(Path.of("build/phase0/paired-reward-reused-receipt.cbor"), invalid.serialize());
        var local = new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()), new DefaultProtocolParamsSupplier(backend.getEpochService()), null);
        var preflight = local.evaluateTx(full.serialize(), Set.of());
        assertTrue(preflight.isSuccessful(), preflight.toString());
        assertFalse(local.evaluateTx(invalid.serialize(), Set.of()).isSuccessful(), "Receipt reuse must fail before waiting for rewards");
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Paired genesis authorization probe with two real positive reward balances and a shared immutable sink; not wallet recovery implementation");
        evidence.put("mintTx", mintId);
        evidence.put("registrationTx", registrationId);
        evidence.put("proposalTx", proposalId);
        evidence.put("coreReward", coreReward);
        evidence.put("moduleReward", moduleReward);
        evidence.put("amountPerCredential", deposit);
        evidence.put("positiveAndNegativeBranchesPreflighted", true);
        evidence.put("protocolParameters", params.getValue());
        Files.writeString(Path.of("build/phase0/paired-reward-pending.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Waiting for paired reward credits from proposal transaction " + proposalId);
        finish(coreReward, moduleReward, deposit, full, invalid, evidence);
    }

    private void finish(String coreReward, String moduleReward, BigInteger deposit, Transaction full,
                        Transaction invalid, LinkedHashMap<String, Object> evidence) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(95).toNanos();
        while (System.nanoTime() < deadline && (!balance(coreReward).equals(deposit) || !balance(moduleReward).equals(deposit)))
            Thread.sleep(15000);
        assertEquals(deposit, balance(coreReward));
        assertEquals(deposit, balance(moduleReward));
        var bad = backend.getTransactionService().submitTransaction(invalid.serialize());
        assertFalse(bad.isSuccessful());
        assertTrue(bad.toString().contains("ValidationTagMismatch") && bad.toString().contains("Caused by: error"), bad.toString());
        var finalEvaluation = backend.getTransactionService().evaluateTx(full.serialize());
        assertTrue(finalEvaluation.isSuccessful(), finalEvaluation.toString());
        String withdrawalId = submit(full);
        assertEquals(BigInteger.ZERO, balance(coreReward));
        assertEquals(BigInteger.ZERO, balance(moduleReward));
        evidence.put("withdrawalTx", withdrawalId);
        evidence.put("receiptReuseNodeFailure", bad.toString());
        evidence.put("transactionBytes", full.serialize().length);
        evidence.put("redeemers", full.getWitnessSet().getRedeemers());
        Files.writeString(Path.of("build/phase0/paired-reward-evidence.json"), JsonUtil.getPrettyJson(evidence));
    }

    private BigInteger balance(String reward) throws Exception {
        var result = backend.getAccountService().getAccountInformation(reward);
        assertTrue(result.isSuccessful(), result.toString());
        return new BigInteger(result.getValue().getWithdrawableAmount());
    }

    @SuppressWarnings("unchecked")
    private Result<List<EvaluationResult>> budget() {
        return Result.success("Preflighted fixed paired budget").withValue(List.of(
                new EvaluationResult(RedeemerTag.Reward, 0, new ExUnits(BigInteger.valueOf(4_000_000), BigInteger.valueOf(1_000_000_000))),
                new EvaluationResult(RedeemerTag.Reward, 1, new ExUnits(BigInteger.valueOf(4_000_000), BigInteger.valueOf(1_000_000_000)))));
    }

    private QuickTxBuilder.TxContext context(BindingFixtures.Pair pair, String coreReward, String moduleReward, Utxo state, Utxo input,
                                             PlutusData auth, BigInteger amount) {
        return builder.compose(new Tx().readFrom(state).attachRewardValidator(pair.core()).attachRewardValidator(pair.module())
                                .withdraw(coreReward, amount, PlutusDataAdapter.toClientLib(auth)).withdraw(moduleReward, amount, PlutusDataAdapter.toClientLib(auth))
                                .payToAddress(creator.enterpriseAddress(), Amount.lovelace(amount)).payToAddress(creator.enterpriseAddress(), Amount.lovelace(amount)),
                        new Tx().collectFrom(List.of(input)).payToAddress(creator.baseAddress(), Amount.ada(16)).from(creator.baseAddress()))
                .feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withSigner(SignerProviders.signerFrom(sponsor)).withSigner(SignerProviders.signerFrom(creator)).withTxEvaluator((cbor, utxos) -> budget());
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
