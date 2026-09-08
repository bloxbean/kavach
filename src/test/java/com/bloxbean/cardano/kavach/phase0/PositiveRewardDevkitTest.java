package com.bloxbean.cardano.kavach.phase0;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
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


@Tag("rewardCredit")
@Timeout(6000)
class PositiveRewardDevkitTest {
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

    @Test void expiredInformationProposalCreditsRealRewards() throws Exception {
        var params = backend.getEpochService().getProtocolParameters();
        assertTrue(params.isSuccessful());
        assertEquals(11, params.getValue().getProtocolMajorVer());
        var deposit = new BigInteger(params.getValue().getKeyDeposit());
        var proposalDeposit = params.getValue().getGovActionDeposit();
        assertTrue(proposalDeposit.compareTo(BigInteger.valueOf(1_000_000_000L)) <= 0, "Re-review faucet limit");
        // CCL selects sponsor UTxOs for receipt outputs before accounting for reward credits.
        topUp(sponsor.baseAddress(), 3500);
        topUp(sponsor.baseAddress(), 10);
        awaitUtxos(sponsor.baseAddress(), 2);
        byte[] authorityHash = authority.hdKeyPair().getPublicKey().getKeyHash();
        var script = script(authorityHash, deposit);
        String reward = AddressProvider.getRewardAddress(script, NETWORK).toBech32();
        String registrationId = submit(builder.compose(new Tx().registerStakeAddress(reward).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        String proposalId = submit(builder.compose(new Tx().createProposal(new InfoAction(), reward, new Anchor("https://example.invalid/kavach-phase0-reward-fixture", new byte[32])).from(sponsor.baseAddress()))
                .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign());
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Expired information-action deposit refunded to a non-delegating script reward account");
        evidence.put("registrationTx", registrationId);
        evidence.put("proposalTx", proposalId);
        evidence.put("rewardAddress", reward);
        evidence.put("scriptCbor", script.getCborHex());
        evidence.put("scriptHash", HexUtil.encodeHexString(script.getScriptHash()));
        evidence.put("authorityHash", HexUtil.encodeHexString(authorityHash));
        evidence.put("protocolParameters", params.getValue());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/positive-reward-pending.json"), JsonUtil.getPrettyJson(evidence));
        // Build every branch before the epoch wait. Save signed devnet transactions (not keys)
        // so an interrupted test cannot discard the only way to redeem this disposable fixture.
        var zero = withdraw(script, reward, BigInteger.ZERO, authorityHash).buildAndSign();
        var partial = withdraw(script, reward, proposalDeposit.subtract(BigInteger.ONE), authorityHash).buildAndSign();
        var full = withdraw(script, reward, proposalDeposit, authorityHash).buildAndSign();
        assertEquals(0, full.getBody().getTtl(), "This reward-only fixture must not expire during the epoch wait");
        Files.write(Path.of("build/phase0/positive-reward-full-withdrawal.cbor"), full.serialize());
        Files.write(Path.of("build/phase0/positive-reward-partial-withdrawal.cbor"), partial.serialize());
        Files.write(Path.of("build/phase0/positive-reward-zero-withdrawal.cbor"), zero.serialize());
        evidence.put("allWithdrawalBranchesPrebuilt", true);
        Files.writeString(Path.of("build/phase0/positive-reward-pending.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Waiting for proposal " + proposalId + " to expire and credit " + reward);
        BigInteger balance = BigInteger.ZERO;
        long deadline = System.nanoTime() + Duration.ofMinutes(95).toNanos();
        while (System.nanoTime() < deadline) {
            var account = backend.getAccountService().getAccountInformation(reward);
            assertTrue(account.isSuccessful(), account.toString());
            balance = new BigInteger(account.getValue().getWithdrawableAmount());
            if (balance.signum() > 0) break;
            Thread.sleep(15000);
        }
        assertEquals(proposalDeposit, balance, "Real reward balance must equal the returned proposal deposit");
        evidence.put("creditedAmount", balance);
        Files.writeString(Path.of("build/phase0/positive-reward-pending.json"), JsonUtil.getPrettyJson(evidence));
        var zeroResult = backend.getTransactionService().submitTransaction(zero.serialize());
        assertFalse(zeroResult.isSuccessful(), "Zero withdrawal must fail while balance is positive");
        assertTrue(zeroResult.toString().contains("Withdrawals"), zeroResult.toString());
        var partialResult = backend.getTransactionService().submitTransaction(partial.serialize());
        assertFalse(partialResult.isSuccessful(), "Partial withdrawal must fail");
        assertTrue(partialResult.toString().contains("Withdrawals"), partialResult.toString());
        String fullId = submit(full);
        var remaining = backend.getAccountService().getAccountInformation(reward);
        assertTrue(remaining.isSuccessful());
        assertEquals("0", remaining.getValue().getWithdrawableAmount());
        evidence.put("creditedAmount", balance);
        evidence.put("fullWithdrawalTx", fullId);
        evidence.put("zeroRejection", zeroResult.toString());
        evidence.put("partialRejection", partialResult.toString());
        evidence.put("redeemers", full.getWitnessSet().getRedeemers());
        evidence.put("sourceSha256", HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("src/main/java/com/bloxbean/cardano/kavach/phase0/RewardLifecycleProbe.java")))));
        Files.writeString(Path.of("build/phase0/positive-reward-evidence.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Full positive withdrawal confirmed: " + fullId);
    }
    private QuickTxBuilder.TxContext withdraw(PlutusV3Script script, String reward, BigInteger amount, byte[] authorityHash) {
        var tx = new Tx().attachRewardValidator(script).withdraw(reward, amount, BigIntPlutusData.of(amount.signum() == 0 ? -1 : 0));
        if (amount.signum() > 0) tx.payToAddress(authority.enterpriseAddress(), Amount.lovelace(amount));
        return funded(tx.from(sponsor.baseAddress())).withRequiredSigners(authorityHash).withSigner(SignerProviders.signerFrom(authority));
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
