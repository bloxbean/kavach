package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.function.helper.DuplicateScriptWitnessChecker;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountDeployment;
import com.bloxbean.cardano.kavach.sdk.ExecutionBudgetMargin;
import com.bloxbean.cardano.kavach.sdk.AccountTransfer;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real local ledger test using disposable keys, full genesis ABI and all three transfer validators.
 */
@Tag("devkit")
@Timeout(600)
class AccountDevkitTest {
    private static final Network NETWORK = new Network(0, 42);
    private final BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "devkit");
    private final QuickTxBuilder builder = new QuickTxBuilder(backend);
    // Separate qualification runs retain their own public manifests and evidence.
    private final Path evidenceDirectory = Path.of(System.getProperty("kavach.phase1EvidenceDirectory", "build/phase1"));
    private final Account sponsor = new Account(NETWORK);
    private final Account creator = new Account(NETWORK);
    private final Account recipient = new Account(NETWORK);
    private final LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
    private RewardSetup reusedRewardSetup;

    /**
     * Public checkpoint setup can be reused; an old account's private signing authority cannot.
     */
    private record RewardSetup(DeploymentDomain domain, Address sink, String coreHash, String moduleHash,
                               String coreReward, String moduleReward, BigInteger deposit, String proposalTx) {
    }

    @Test
    void createFundAndSpendWithAllRequiredValidators() throws Exception {
        createFundAndSpend(false);
    }

    /**
     * Runs the complete account operation; the reward profile waits for real ledger credits.
     */
    void createFundAndSpend(boolean positiveRewards) throws Exception {
        createFundAndSpend(positiveRewards, false, false);
    }

    @Test
    void eightInputNativeTokenPartialSpend() throws Exception {
        createFundAndSpend(false, true, false);
    }

    @Test
    void wholeDepositWith140NativeTokens() throws Exception {
        createFundAndSpend(false, false, true);
    }

    @Test
    void eightInputEightRecipientNativeSpend() throws Exception {
        createFundAndSpend(false, true, false, 8);
    }

    private void createFundAndSpend(boolean positiveRewards, boolean nativePartial, boolean whole) throws Exception {
        createFundAndSpend(positiveRewards, nativePartial, whole, 1);
    }

    /**
     * Each profile initializes a fresh disposable account and exercises all required validators.
     */
    private void createFundAndSpend(boolean positiveRewards, boolean nativePartial, boolean whole, int recipientCount) throws Exception {
        assertTrue(backend.getEpochService().getProtocolParameters().isSuccessful(), "DevKit must be available");
        if (positiveRewards && Boolean.getBoolean("kavach.reusePhase1RewardSetup"))
            reusedRewardSetup = loadRewardSetup();
        topUp(sponsor.baseAddress(), positiveRewards ? 9000 : 1000);
        topUp(sponsor.baseAddress(), 20);
        topUp(creator.baseAddress(), 20);
        awaitUtxos(sponsor.baseAddress(), 2);
        var seed = awaitUtxos(creator.baseAddress(), 1).getFirst();
        byte[] discriminator = new byte[32];
        new SecureRandom().nextBytes(discriminator);
        var domain = new DeploymentDomain(BigInteger.ZERO, BigInteger.valueOf(42), discriminator);
        var sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(sponsor.hdKeyPair().getPublicKey().getKeyHash())), Optional.empty());
        if (reusedRewardSetup != null) {
            domain = reusedRewardSetup.domain();
            sink = reusedRewardSetup.sink();
        }
        var scripts = AccountDeployment.derive(domain, ref(seed), creator.hdKeyPair().getPublicKey().getKeyHash(), sink, sink);
        if (reusedRewardSetup != null) {
            assertEquals(reusedRewardSetup.coreHash(), HexUtil.encodeHexString(scripts.checkpoint().getScriptHash()));
            assertEquals(reusedRewardSetup.moduleHash(), HexUtil.encodeHexString(scripts.module().getScriptHash()));
        }
        String receiptSinkAddress = AddressProvider.getEntAddress(com.bloxbean.cardano.client.address.Credential.fromKey(
                ((Credential.PubKeyCredential) sink.credential()).hash().hash()), NETWORK).toBech32();
        var keys = new AccountFixtures();
        var state = AccountDeployment.genesis(scripts, domain, AccountCodec.data(keys.config), BigInteger.valueOf(86400000), BigInteger.valueOf(3600000));
        String holder = AddressProvider.getEntAddress(scripts.state(), NETWORK).toBech32();
        String account = AddressProvider.getEntAddress(scripts.asset(), NETWORK).toBech32();
        String coreReward = AddressProvider.getRewardAddress(scripts.checkpoint(), NETWORK).toBech32();
        String moduleReward = AddressProvider.getRewardAddress(scripts.module(), NETWORK).toBech32();
        // Each individually published script fits under the transaction byte limit.
        var references = new ArrayList<Utxo>();
        for (var script : List.of(scripts.checkpoint(), scripts.module(), scripts.nft(), scripts.asset())) {
            String id = submit(builder.compose(new Tx().payToAddress(holder, Amount.ada(80), script).from(sponsor.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "publication-" + references.size());
            references.add(awaitUtxos(holder, 1).stream().filter(u -> u.getTxHash().equals(id)).findFirst().orElseThrow());
        }
        if (reusedRewardSetup == null)
            submit(builder.compose(new Tx().registerStakeAddress(coreReward).registerStakeAddress(moduleReward).from(sponsor.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "registration");
        var proofEnvelope = new GenesisProofEnvelope("KAVACH_GENESIS_POSSESSION_V1".getBytes(StandardCharsets.UTF_8), domain,
                state.accountId(), state.coreBinding(), state.authModule(), WireFormat.digest(state.authConfig()));
        var digest = WireFormat.digest(AccountCodec.data(proofEnvelope));
        var possession = AccountFixtures.list(AccountFixtures.sign(0, keys.keys.get(0), digest), AccountFixtures.sign(1, keys.keys.get(1), digest), AccountFixtures.sign(2, keys.keys.get(2), digest));
        var genesis = new GenesisModuleRedeemer(BigInteger.ONE, state, possession, JulcList.empty());
        String policy = HexUtil.encodeHexString(state.accountId().policy());
        var creation = new Tx().collectFrom(List.of(seed)).mintAsset(scripts.nft(), new com.bloxbean.cardano.client.transaction.spec.Asset("", BigInteger.ONE), BigIntPlutusData.of(0))
                .payToContract(holder, List.of(Amount.ada(12), Amount.asset(policy, "", 1)), PlutusDataAdapter.toClientLib(AccountCodec.data(state)))
                .attachRewardValidator(scripts.module()).withdraw(moduleReward, BigInteger.ZERO, PlutusDataAdapter.toClientLib(AccountCodec.data(genesis)))
                .readFrom(references.get(1)).readFrom(references.get(2)).from(creator.baseAddress());
        var creationTx = builder.compose(creation).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withRequiredSigners(creator.hdKeyPair().getPublicKey().getKeyHash()).withSigner(SignerProviders.signerFrom(creator)).withSigner(SignerProviders.signerFrom(sponsor))
                .withTxEvaluator(conservativeEvaluator()).withReferenceScripts(scripts.nft(), scripts.module()).preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses()).removeDuplicateScriptWitnesses(true).buildAndSign();
        String creationId = submit(creationTx, "creation");
        var stateUtxo = awaitUtxos(holder, 5).stream().filter(u -> u.getTxHash().equals(creationId) && u.getAmount().stream().anyMatch(a -> a.getUnit().equals(policy))).findFirst().orElseThrow();
        int inputCount = nativePartial ? 8 : 1;
        var funding = new Tx();
        var tokens = new ArrayList<Amount>();
        if (nativePartial || whole) {
            var tokenPolicy = new ScriptPubkey(HexUtil.encodeHexString(sponsor.hdKeyPair().getPublicKey().getKeyHash()));
            var minted = new ArrayList<com.bloxbean.cardano.client.transaction.spec.Asset>();
            for (int index = 0; index < (whole ? 140 : 8); index++) {
                byte[] name = new byte[whole ? 32 : 1];
                name[0] = (byte) index;
                minted.add(new com.bloxbean.cardano.client.transaction.spec.Asset("0x" + HexUtil.encodeHexString(name), BigInteger.ONE));
                tokens.add(Amount.asset(tokenPolicy.getPolicyId() + HexUtil.encodeHexString(name), BigInteger.ONE));
            }
            funding.mintAssets(tokenPolicy, minted);
        }
        for (int index = 0; index < inputCount; index++) {
            var amount = new ArrayList<Amount>();
            amount.add(Amount.ada(whole ? 40 : 20));
            if (index == 0) amount.addAll(tokens);
            funding.payToAddress(account, amount);
        }
        submit(builder.compose(funding.from(sponsor.baseAddress())).withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "funding");
        var accountInputs = awaitUtxos(account, inputCount).stream()
                .sorted(Comparator.comparing(Utxo::getTxHash).thenComparingInt(Utxo::getOutputIndex)).toList();
        assertEquals(inputCount, accountInputs.size());
        var asset = accountInputs.getFirst();
        var allRecipientAmounts = new ArrayList<List<Amount>>();
        for (int index = 0; index < recipientCount; index++) {
            var amounts = new ArrayList<Amount>();
            amounts.add(Amount.ada(whole ? 41 : 2));
            if (whole) amounts.addAll(tokens);
            else if (nativePartial) amounts.add(tokens.get(index));
            allRecipientAmounts.add(amounts);
        }
        var changeAmounts = new ArrayList<Amount>();
        changeAmounts.add(Amount.ada(20 * inputCount - 2 * recipientCount));
        if (nativePartial) changeAmounts.addAll(tokens.subList(recipientCount, tokens.size()));
        Value resolvedWholeValue = Value.lovelace(BigInteger.valueOf(40000000));
        for (var amount : tokens.stream().sorted(Comparator.comparing(Amount::getUnit).reversed()).toList()) {
            resolvedWholeValue = resolvedWholeValue.merge(Value.singleton(new PolicyId(HexUtil.decodeHexString(amount.getUnit().substring(0, 56))),
                    new TokenName(HexUtil.decodeHexString(amount.getUnit().substring(56))), amount.getQuantity()));
        }
        BigInteger rewardAmount = BigInteger.ZERO;
        if (positiveRewards) rewardAmount = awaitProposalRefunds(coreReward, moduleReward, state);
        var latest = backend.getBlockService().getLatestBlock().getValue();
        long lower = latest.getSlot();
        long upper = lower + 180;
        var destination = new Address(new Credential.PubKeyCredential(new PubKeyHash(recipient.hdKeyPair().getPublicKey().getKeyHash())), Optional.empty());
        var recipients = new ArrayList<Recipient>();
        for (int index = 0; index < allRecipientAmounts.size(); index++) {
            var signedAssets = new ArrayList<Asset>();
            for (var amount : allRecipientAmounts.get(index).stream().sorted(Comparator.comparing(a -> a.getUnit().equals("lovelace") ? "" : a.getUnit())).toList()) {
                String unit = amount.getUnit();
                signedAssets.add(new Asset(unit.equals("lovelace") ? new byte[0] : HexUtil.decodeHexString(unit.substring(0, 56)),
                        unit.equals("lovelace") ? new byte[0] : HexUtil.decodeHexString(unit.substring(56)), amount.getQuantity()));
            }
            recipients.add(new Recipient(BigInteger.valueOf(index), destination, AccountFixtures.list(signedAssets.toArray(Asset[]::new))));
        }
        Action action = whole ? new TransferWholeUtxo(ref(asset), BigInteger.ZERO, destination, WireFormat.ledgerValueDigest(resolvedWholeValue.toPlutusData()))
                : new Spend(AccountFixtures.list(accountInputs.stream().map(AccountDevkitTest::ref).toArray(TxOutRef[]::new)),
                AccountFixtures.list(recipients.toArray(Recipient[]::new)), BigInteger.ZERO);
        var intent = new IntentEnvelope(WireFormat.protocolTag(), new IntentDomain(BigInteger.ONE, domain, state.accountId(), state.coreBinding(), BigInteger.ZERO, ref(stateUtxo)),
                new Validity(BigInteger.valueOf(latest.getTime() * 1000), BigInteger.valueOf((latest.getTime() + 180) * 1000)), action);
        byte[] intentDigest = AccountCodec.intentDigest(intent, whole ? resolvedWholeValue.toPlutusData() : null);
        JulcList<RewardReceipt> receipts = JulcList.empty();
        if (positiveRewards) {
            var a = new RewardReceipt(new Credential.ScriptCredential(new ScriptHash(scripts.checkpoint().getScriptHash())), BigInteger.TWO);
            var b = new RewardReceipt(new Credential.ScriptCredential(new ScriptHash(scripts.module().getScriptHash())), BigInteger.valueOf(3));
            receipts = Arrays.compareUnsigned(scripts.checkpoint().getScriptHash(), scripts.module().getScriptHash()) < 0
                    ? AccountFixtures.list(a, b) : AccountFixtures.list(b, a);
        }
        var proof = new Proof(BigInteger.ZERO, AccountFixtures.list(AccountFixtures.sign(0, keys.keys.get(0), intentDigest)));
        var tx = new Tx();
        for (var amounts : allRecipientAmounts) tx.payToAddress(recipient.enterpriseAddress(), amounts);
        tx.readFrom(references.get(0)).readFrom(references.get(1)).readFrom(references.get(3));
        if (!whole) tx.payToAddress(account, changeAmounts);
        if (positiveRewards) {
            // Same immutable sink, distinct outputs, each with a sponsor-funded top-up.
            tx.payToAddress(receiptSinkAddress, Amount.lovelace(rewardAmount.add(BigInteger.valueOf(2000000))))
                    .payToAddress(receiptSinkAddress, Amount.lovelace(rewardAmount.add(BigInteger.valueOf(2000000))));
        }
        AccountTransfer.attach(tx, scripts, state, stateUtxo, accountInputs, intent, proof, receipts, rewardAmount, rewardAmount);
        // CCL already counts the sponsor's key from resolved fee/collateral inputs. Registering
        // it again via withSigner counts an additional dummy witness during fee balancing.
        // This fixture needs only that one key witness; intent signatures are separate evidence.
        var unsignedSpend = builder.compose(tx).feePayer(sponsor.baseAddress()).collateralPayer(sponsor.baseAddress())
                .withTxEvaluator(conservativeEvaluator()).validFrom(lower).validTo(upper).withReferenceScripts(scripts.asset(), scripts.checkpoint(), scripts.module()).preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses()).removeDuplicateScriptWitnesses(true).build();
        var spend = sponsor.sign(unsignedSpend);
        var evaluated = backend.getTransactionService().evaluateTx(spend.serialize());
        assertTrue(evaluated.isSuccessful(), evaluated.toString());
        // A valid transaction-body witness cannot override the signed semantic recipient.
        var substituted = Transaction.deserialize(spend.serialize());
        substituted.getBody().getOutputs().getFirst().setAddress(sponsor.enterpriseAddress());
        substituted.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
        substituted = sponsor.sign(substituted);
        assertTrue(substituted.isValid(), "Rejected script transactions must not consume collateral");
        var rejection = backend.getTransactionService().submitTransaction(substituted.serialize());
        assertFalse(rejection.isSuccessful(), "Node must reject recipient substitution");
        assertTrue(rejection.toString().contains("ValidationTagMismatch") && rejection.toString().contains("Caused by: error"), rejection.toString());
        evidence.put("recipientSubstitutionRejected", true);
        if (nativePartial || whole) {
            // Move a required token to sponsor change: ledger value balances, account semantics fail.
            var missing = Transaction.deserialize(spend.serialize());
            var source = missing.getBody().getOutputs().getFirst().getValue();
            var policyAssets = source.getMultiAssets().getFirst();
            var stolen = policyAssets.getAssets().removeFirst();
            if (policyAssets.getAssets().isEmpty()) source.getMultiAssets().clear();
            var sponsorValue = missing.getBody().getOutputs().getLast().getValue();
            sponsorValue.getMultiAssets().add(new MultiAsset(policyAssets.getPolicyId(), new ArrayList<>(List.of(stolen))));
            // Moving the policy to another output increases bytes; retain a sufficient explicit fee.
            sponsorValue.setCoin(sponsorValue.getCoin().subtract(BigInteger.valueOf(50000)));
            missing.getBody().setFee(missing.getBody().getFee().add(BigInteger.valueOf(50000)));
            // Collateral was balanced against the old fee. Increase its reserved portion too.
            var extraCollateral = BigInteger.valueOf(100000);
            assertNotNull(missing.getBody().getTotalCollateral());
            assertNotNull(missing.getBody().getCollateralReturn());
            missing.getBody().setTotalCollateral(missing.getBody().getTotalCollateral().add(extraCollateral));
            var collateralReturn = missing.getBody().getCollateralReturn().getValue();
            collateralReturn.setCoin(collateralReturn.getCoin().subtract(extraCollateral));
            missing.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
            missing = sponsor.sign(missing);
            var missingRejection = backend.getTransactionService().submitTransaction(missing.serialize());
            Files.createDirectories(evidenceDirectory);
            Files.writeString(evidenceDirectory.resolve("token-diversion-" + (whole ? "whole" : "partial") + "-rejection.txt"), missingRejection.toString());
            assertFalse(missingRejection.isSuccessful());
            assertTrue(missingRejection.toString().contains("ValidationTagMismatch") && missingRejection.toString().contains("Caused by: error"), "Token diversion must fail script validation");
            evidence.put("tokenDiversionRejected", true);
        }
        if (positiveRewards) {
            // Underpay one reward receipt while retaining ledger value conservation.
            var underpaid = Transaction.deserialize(spend.serialize());
            var receiptOutput = underpaid.getBody().getOutputs().get(3);
            var displaced = receiptOutput.getValue().getCoin().subtract(BigInteger.ONE);
            receiptOutput.getValue().setCoin(BigInteger.ONE);
            var sponsorChange = underpaid.getBody().getOutputs().getLast();
            assertTrue(underpaid.getBody().getOutputs().size() > 4, "Separate sponsor change is required");
            sponsorChange.getValue().setCoin(sponsorChange.getValue().getCoin().add(displaced));
            // Keep receipt min-ADA valid: only its allocation, not ledger output validity, must fail.
            BigInteger minAda = BigInteger.valueOf(2000000);
            receiptOutput.getValue().setCoin(minAda);
            sponsorChange.getValue().setCoin(sponsorChange.getValue().getCoin().subtract(minAda.subtract(BigInteger.ONE)));
            underpaid.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
            underpaid = sponsor.sign(underpaid);
            var badReceipt = backend.getTransactionService().submitTransaction(underpaid.serialize());
            assertFalse(badReceipt.isSuccessful());
            assertTrue(badReceipt.toString().contains("ValidationTagMismatch") && badReceipt.toString().contains("Caused by: error"), "Underpaid reward receipt must fail script validation");
            evidence.put("underpaidRewardReceiptRejected", true);
        }
        submit(spend, "spend");
        if (positiveRewards) {
            assertEquals(BigInteger.ZERO, rewardBalance(coreReward));
            assertEquals(BigInteger.ZERO, rewardBalance(moduleReward));
            evidence.put("positiveRewardsCleared", true);
        }
        var replay = backend.getTransactionService().submitTransaction(spend.serialize());
        assertFalse(replay.isSuccessful(), "Consumed inputs make the same intent unusable");
        evidence.put("replayRejected", true);
        if (!whole)
            assertTrue(awaitUtxos(account, 1).stream().noneMatch(u -> accountInputs.stream().anyMatch(old -> old.getTxHash().equals(u.getTxHash()) && old.getOutputIndex() == u.getOutputIndex())));
        else assertTrue(backend.getUtxoService().getUtxos(account, 100, 1, OrderEnum.desc).getValue().isEmpty());
        evidence.put("feeBreakdown", FeeEvidence.analyze(spend, backend.getEpochService().getProtocolParameters().getValue(),
                List.of(scripts.asset(), scripts.checkpoint(), scripts.module())));
        evidence.put("recipientCount", recipientCount);
        evidence.put("budgetAllowancePercent", ExecutionBudgetMargin.DEFAULT_PERCENT);
        evidence.put("accountInputCount", inputCount);
        evidence.put("nativeTokenCount", tokens.size());
        evidence.put("wholeTransfer", whole);
        assertTrue(awaitUtxos(holder, 5).stream().anyMatch(u -> u.getTxHash().equals(stateUtxo.getTxHash()) && u.getOutputIndex() == stateUtxo.getOutputIndex()), "State remains a reference");
        evidence.put("scope", "Phase 1 full creation and transfer; sealed development state, not production recovery");
        evidence.put("protocolParameters", backend.getEpochService().getProtocolParameters().getValue());
        evidence.put("state", PlutusDataAdapter.toClientLib(AccountCodec.data(state)).serializeToHex());
        Files.createDirectories(evidenceDirectory);
        Files.writeString(evidenceDirectory.resolve(positiveRewards ? "positive-reward-devkit-evidence.json" : whole ? "whole-native-devkit-evidence.json" : nativePartial ? (recipientCount == 8 ? "eight-recipient-native-devkit-evidence.json" : "partial-native-devkit-evidence.json") : "devkit-evidence.json"), JsonUtil.getPrettyJson(evidence));
    }

    /**
     * Reconstructs shared checkpoint parameters exclusively from retained public ledger data.
     * Verifies both derived script hashes and zero current balances before creating a new
     * account. This neither resumes the old account nor obtains its private keys.
     */
    private RewardSetup loadRewardSetup() throws Exception {
        var saved = JsonUtil.parseJson(Files.readString(evidenceDirectory.resolve("positive-reward-pending.json")));
        var datum = (PlutusData.ConstrData) PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(
                HexUtil.decodeHexString(saved.get("state").asText())));
        var domainData = ((PlutusData.ConstrData) datum.fields().get(2)).fields();
        var domain = new DeploymentDomain(((PlutusData.IntData) domainData.get(0)).value(), ((PlutusData.IntData) domainData.get(1)).value(), ((PlutusData.BytesData) domainData.get(2)).value());
        var binding = ((PlutusData.ConstrData) datum.fields().get(3)).fields();
        String expectedCore = HexUtil.encodeHexString(((PlutusData.BytesData) binding.get(2)).value());
        String expectedModule = HexUtil.encodeHexString(((PlutusData.BytesData) ((PlutusData.ConstrData) datum.fields().get(5)).fields().get(0)).value());
        var funding = backend.getTransactionService().getTransactionUtxos(saved.get("funding").get("tx").asText());
        assertTrue(funding.isSuccessful(), "Original public funding transaction must be available");
        Address sink = null;
        for (var output : funding.getValue().getOutputs()) {
            var address = new com.bloxbean.cardano.client.address.Address(output.getAddress());
            if (!address.isScriptHashInPaymentPart() && address.getPaymentCredentialHash().isPresent()) {
                var candidate = new Address(new Credential.PubKeyCredential(new PubKeyHash(address.getPaymentCredentialHash().orElseThrow())), Optional.empty());
                var graph = AccountDeployment.derive(domain, new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), new byte[28], candidate, candidate);
                if (HexUtil.encodeHexString(graph.checkpoint().getScriptHash()).equals(expectedCore)
                        && HexUtil.encodeHexString(graph.module().getScriptHash()).equals(expectedModule))
                    sink = candidate;
            }
        }
        assertNotNull(sink, "Current core/module artifacts must match the existing public checkpoint setup exactly");
        String coreReward = saved.get("coreReward").asText();
        String moduleReward = saved.get("moduleReward").asText();
        assertEquals(BigInteger.ZERO, rewardBalance(coreReward), "New account creation must precede the refund");
        assertEquals(BigInteger.ZERO, rewardBalance(moduleReward), "New account creation must precede the refund");
        var setup = new RewardSetup(domain, sink, expectedCore, expectedModule, coreReward, moduleReward,
                saved.get("rewardDepositPerCredential").bigIntegerValue(), saved.get("reward-proposals").get("tx").asText());
        System.out.println("Verified unchanged public checkpoint setup; reusing proposal " + setup.proposalTx());
        return setup;
    }

    /**
     * Credits each checkpoint through actual Conway information-proposal deposit refunds.
     * Retains a public pending manifest. Signing keys stay only in this test process; a
     * stopped process cannot resume this account and must not be mistaken for a live wait.
     */
    private BigInteger awaitProposalRefunds(String coreReward, String moduleReward, AccountState state) throws Exception {
        var parameters = backend.getEpochService().getProtocolParameters();
        assertTrue(parameters.isSuccessful());
        BigInteger deposit = parameters.getValue().getGovActionDeposit();
        assertTrue(deposit.signum() > 0 && deposit.compareTo(BigInteger.valueOf(1000000000)) <= 0);
        if (reusedRewardSetup == null) {
            var proposal = new Tx().createProposal(new InfoAction(), coreReward, new Anchor("https://example.invalid/kavach-phase1-reward-core", new byte[32]))
                    .createProposal(new InfoAction(), moduleReward, new Anchor("https://example.invalid/kavach-phase1-reward-module", new byte[32])).from(sponsor.baseAddress());
            submit(builder.compose(proposal).withSigner(SignerProviders.signerFrom(sponsor)).buildAndSign(), "reward-proposals");
        } else {
            assertEquals(reusedRewardSetup.coreReward(), coreReward);
            assertEquals(reusedRewardSetup.moduleReward(), moduleReward);
            assertEquals(reusedRewardSetup.deposit(), deposit);
            evidence.put("reusedProposalTx", reusedRewardSetup.proposalTx());
            evidence.put("reusedCheckpointSetup", true);
        }
        evidence.put("coreReward", coreReward);
        evidence.put("moduleReward", moduleReward);
        evidence.put("rewardDepositPerCredential", deposit);
        evidence.put("state", PlutusDataAdapter.toClientLib(AccountCodec.data(state)).serializeToHex());
        Files.createDirectories(evidenceDirectory);
        Files.writeString(evidenceDirectory.resolve(reusedRewardSetup == null ? "positive-reward-pending.json" : "positive-reward-reused-pending.json"), JsonUtil.getPrettyJson(evidence));
        long deadline = System.nanoTime() + Duration.ofMinutes(95).toNanos();
        while (System.nanoTime() < deadline) {
            var core = rewardBalance(coreReward);
            var module = rewardBalance(moduleReward);
            if (core.equals(deposit) && module.equals(deposit)) return deposit;
            assertTrue(core.compareTo(deposit) <= 0 && module.compareTo(deposit) <= 0, "Unexpected external reward credit");
            System.out.println("Phase 1 reward wait: core=" + core + " module=" + module);
            Thread.sleep(15000);
        }
        throw new AssertionError("Actual checkpoint proposal refunds were not credited within 95 minutes");
    }

    /**
     * Queries the indexed ledger reward balance; service failures are never treated as zero.
     */
    private BigInteger rewardBalance(String address) throws Exception {
        var result = backend.getAccountService().getAccountInformation(address);
        assertTrue(result.isSuccessful() && result.getValue() != null, "DevKit reward account lookup failed");
        return new BigInteger(result.getValue().getWithdrawableAmount());
    }

    /**
     * Uses the shared SDK allowance before CCL balances fees and collateral against current limits.
     */
    private TransactionEvaluator conservativeEvaluator() throws Exception {
        var parameters = backend.getEpochService().getProtocolParameters();
        assertTrue(parameters.isSuccessful(), "Current ledger limits required");
        return new ExecutionBudgetMargin((cbor, inputs) -> backend.getTransactionService().evaluateTx(cbor), parameters.getValue());
    }

    /**
     * Records only public transaction data; disposable signing secrets never enter evidence.
     */
    private String submit(Transaction tx, String name) throws Exception {
        assertTrue(tx.serialize().length <= 16384, name + " transaction exceeds ledger byte limit");
        var result = backend.getTransactionService().submitTransaction(tx.serialize());
        assertTrue(result.isSuccessful(), name + ": " + result);
        for (int i = 0; i < 60; i++) {
            var confirmed = backend.getTransactionService().getTransaction(result.getValue());
            if (confirmed.isSuccessful() && confirmed.getValue() != null) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("tx", result.getValue());
                entry.put("bytes", tx.serialize().length);
                entry.put("fee", tx.getBody().getFee());
                entry.put("redeemers", tx.getWitnessSet().getRedeemers());
                evidence.put(name, entry);
                System.out.println(name + " confirmed " + result.getValue() + " fee=" + tx.getBody().getFee());
                return result.getValue();
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Unconfirmed " + name + ": " + result.getValue());
    }

    private static TxOutRef ref(Utxo utxo) {
        return new TxOutRef(new TxId(HexUtil.decodeHexString(utxo.getTxHash())), BigInteger.valueOf(utxo.getOutputIndex()));
    }

    private List<Utxo> awaitUtxos(String address, int minimum) throws Exception {
        for (int i = 0; i < 60; i++) {
            var result = backend.getUtxoService().getUtxos(address, 100, 1, OrderEnum.desc);
            if (result.isSuccessful() && result.getValue().size() >= minimum) return result.getValue();
            Thread.sleep(1000);
        }
        throw new AssertionError("DevKit UTxOs unavailable at " + address);
    }

    private void topUp(String address, long ada) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:10000/local-cluster/api/addresses/topup"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"address\":\"" + address + "\",\"adaAmount\":" + ada + "}")).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("\"status\":false"));
        }
    }
}
