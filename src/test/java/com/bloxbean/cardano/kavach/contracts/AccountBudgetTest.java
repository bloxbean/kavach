package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Measures the sum of every executed immutable asset input, checkpoint and signature module, not isolated components.
 */
class AccountBudgetTest {
    @ParameterizedTest
    @CsvSource({"false,0", "true,0", "false,8", "true,8"})
    void maximumInputOutputCountsAndEightSignaturesFitTogether(boolean positiveRewards, int recipientCount) throws Exception {
        var f = new AccountFixtures();
        var keys = new ArrayList<KeyPair>();
        var registry = new ArrayList<KeyEntry>();
        for (int i = 0; i < 16; i++) {
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            keys.add(key);
            registry.add(new KeyEntry(BigInteger.valueOf(i), AccountFixtures.publicKey(key)));
        }
        var config = new Ed25519Config(BigInteger.ONE, AccountFixtures.list(registry.toArray(KeyEntry[]::new)),
                AccountFixtures.policy(8, 0, 1, 2, 3, 4, 5, 6, 7), AccountFixtures.policy(8, 8, 9, 10, 11, 12, 13, 14, 15),
                AccountFixtures.policy(8, 0, 1, 2, 3, 4, 5, 6, 7), AccountFixtures.policy(8, 8, 9, 10, 11, 12, 13, 14, 15),
                AccountFixtures.policy(8, 0, 1, 2, 3, 4, 5, 6, 7), AccountFixtures.policy(8, 8, 9, 10, 11, 12, 13, 14, 15));
        WireFormat.validateConfig(AccountCodec.data(config));
        var state = AccountAdversarialTest.field(AccountCodec.data(f.state), AccountCodec.data(config), 6);
        WireFormat.validateState(state);
        var refs = new ArrayList<TxOutRef>();
        var inputs = new ArrayList<PlutusData>();
        var outputs = new ArrayList<PlutusData>();
        // Twelve native entries across inputs/outputs, eleven distinct native assets.
        for (int i = 0; i < 8; i++) {
            refs.add(AccountFixtures.ref(30 + i));
            var assets = new ArrayList<Integer>();
            for (int j = 0; j < 12; j++) if (j % 8 == i) assets.add(j % 11);
            inputs.add(new TxInInfo(refs.get(i), new TxOut(f.accountAddress, value(20000000, assets), new OutputDatum.NoOutputDatum(), Optional.empty())).toPlutusData());
        }
        for (int i = 0; i < 8; i++)
            inputs.add(new TxInInfo(AccountFixtures.ref(100 + i), AccountFixtures.output(f.sink, 2000000)).toPlutusData());
        int changeCount = positiveRewards ? 14 : 16;
        var signedRecipients = new ArrayList<Recipient>();
        var recipientAddress = new Address(f.sink.credential(), Optional.of(new StakingCredential.StakingHash(new Credential.ScriptCredential(new ScriptHash(new byte[28])))));
        for (int i = 0; i < changeCount; i++) {
            var assets = new ArrayList<Integer>();
            if (recipientCount == 0) {
                if (i < 12) assets.add(i % 11);
            } else if (i >= recipientCount) for (int j = 0; j < 12; j++)
                if (j % (changeCount - recipientCount) == i - recipientCount) assets.add(j % 11);
            if (i < recipientCount) signedRecipients.add(new Recipient(BigInteger.valueOf(i), recipientAddress,
                    AccountFixtures.list(new Asset(new byte[0], new byte[0], BigInteger.valueOf(10000000)))));
            outputs.add(new TxOut(i < recipientCount ? recipientAddress : f.accountAddress, value(i == changeCount - 1 ? 160000000 - (changeCount - 1) * 10000000 : 10000000, assets), new OutputDatum.NoOutputDatum(), Optional.empty()).toPlutusData());
        }
        JulcList<RewardReceipt> receipts = JulcList.empty();
        if (positiveRewards) {
            outputs.add(AccountFixtures.output(f.sink, 2000000).toPlutusData());
            outputs.add(AccountFixtures.output(f.sink, 2000000).toPlutusData());
            var coreReceipt = new RewardReceipt(f.core, BigInteger.valueOf(14));
            var moduleReceipt = new RewardReceipt(f.module, BigInteger.valueOf(15));
            receipts = Arrays.compareUnsigned(f.coreScript.getScriptHash(), f.moduleScript.getScriptHash()) < 0
                    ? AccountFixtures.list(coreReceipt, moduleReceipt) : AccountFixtures.list(moduleReceipt, coreReceipt);
        }
        var ordinary = f.spend(0);
        var intent = new IntentEnvelope(ordinary.protocolTag(), ordinary.domain(), ordinary.validity(),
                new Spend(AccountFixtures.list(refs.toArray(TxOutRef[]::new)), AccountFixtures.list(signedRecipients.toArray(Recipient[]::new)), BigInteger.ZERO));
        var digest = AccountCodec.intentDigest(intent, null);
        var proofs = new ArrayList<Signature>();
        for (int i = 0; i < 8; i++) proofs.add(AccountFixtures.sign(i, keys.get(i), digest));
        var auth = new ModuleRedeemer(BigInteger.ONE, intent, Optional.of(new Proof(BigInteger.ZERO, AccountFixtures.list(proofs.toArray(Signature[]::new)))), JulcList.empty(), receipts);
        long memory = 0;
        long cpu = 0;
        long allocatedMemory = 0;
        long allocatedCpu = 0;
        var budgets = new LinkedHashMap<String, Object>();
        for (int execution = 0; execution < 10; execution++) {
            String role = execution < 8 ? "asset" : execution == 8 ? "core" : "module";
            var contextBuilder = f.context(role, intent, auth);
            if (positiveRewards) contextBuilder.withdrawal(f.core, BigInteger.ONE).withdrawal(f.module, BigInteger.ONE);
            for (int signer = 0; signer < 16; signer++) {
                byte[] hash = new byte[28];
                hash[27] = (byte) signer;
                contextBuilder.signer(new PubKeyHash(hash));
            }
            var context = contextBuilder.buildPlutusData();
            context = AccountAdversarialTest.field(context, new PlutusData.ListData(inputs), 0, 0);
            context = AccountAdversarialTest.field(context, new PlutusData.ListData(outputs), 0, 2);
            context = AccountAdversarialTest.field(context, state, 0, 1, 0, 1, 2, 0);
            var info = TxInfo.fromPlutusData(((PlutusData.ConstrData) context).fields().getFirst());
            var redeemers = info.redeemers();
            for (var ref : refs)
                redeemers = redeemers.insert(new ScriptPurpose.Spending(ref), AccountCodec.data(new AssetRedeemer(BigInteger.ONE, digest)));
            var references = new ArrayList<PlutusData>();
            references.add(f.stateInput().toPlutusData());
            for (int i = 0; i < 3; i++)
                references.add(new TxInInfo(AccountFixtures.ref(200 + i), AccountFixtures.output(f.sink, 1)).toPlutusData());
            context = AccountAdversarialTest.field(context, new PlutusData.ListData(references), 0, 1);
            context = AccountAdversarialTest.field(context, state, 0, 1, 0, 1, 2, 0);
            // Encode redeemer map with the ledger adapter, avoiding speculative Java map ordering.
            var revised = new TxInfo(info.inputs(), info.referenceInputs(), info.outputs(), info.fee(), info.mint(), info.certificates(), info.withdrawals(), info.validRange(),
                    info.signatories(), redeemers, info.datums(), info.id(), info.votes(), info.proposalProcedures(), info.currentTreasuryAmount(), info.treasuryDonation());
            context = AccountAdversarialTest.field(context, revised.toPlutusData().fields().get(9), 0, 9);
            if (execution < 8)
                context = AccountAdversarialTest.field(context, new ScriptInfo.SpendingScript(refs.get(execution), Optional.empty()).toPlutusData(), 2);
            var evaluated = f.evaluate(role, context);
            var result = assertInstanceOf(EvalResult.Success.class, evaluated, role + execution + ": " + evaluated);
            memory += result.consumed().memoryUnits();
            cpu += result.consumed().cpuSteps();
            allocatedMemory += (result.consumed().memoryUnits() * 105 + 99) / 100;
            allocatedCpu += (result.consumed().cpuSteps() * 105 + 99) / 100;
            budgets.put(role + execution, result.consumed().toString());
        }
        budgets.put("positiveRewards", positiveRewards);
        budgets.put("recipientCount", recipientCount);
        budgets.put("totalMemory", memory);
        budgets.put("totalCpu", cpu);
        budgets.put("memoryWithFivePercentAllowance", allocatedMemory);
        budgets.put("cpuWithFivePercentAllowance", allocatedCpu);
        budgets.put("scope", "8 account + 8 sponsor inputs, 4 references, 16 outputs, 16 transaction signatories, 12 native entries with 32-byte names, 16 registry keys, all six roles at 8 members, 8 spend signatures, base-address recipients; synthetic full script-context composition");
        Files.createDirectories(Path.of("build/phase1"));
        Files.writeString(Path.of("build/phase1/combined-" + (positiveRewards ? "positive-reward-" : "") + (recipientCount == 0 ? "" : "eight-recipient-") + "budget.json"), JsonUtil.getPrettyJson(budgets));
        assertTrue(allocatedMemory <= 16500000, "Combined memory with SDK allowance " + allocatedMemory);
        assertTrue(allocatedCpu <= 10000000000L, "Combined CPU with SDK allowance " + allocatedCpu);
    }

    /**
     * Builds canonical ledger Values with 28-byte policies and maximum-length asset names.
     */
    static Value value(long ada, List<Integer> assets) {
        Value result = Value.lovelace(BigInteger.valueOf(ada));
        for (var id : assets.stream().sorted(Comparator.reverseOrder()).toList()) {
            byte[] policy = new byte[28];
            policy[0] = (byte) (id + 1);
            result = result.merge(Value.singleton(new PolicyId(policy), new TokenName(new byte[32]), BigInteger.ONE));
        }
        return result;
    }
}
