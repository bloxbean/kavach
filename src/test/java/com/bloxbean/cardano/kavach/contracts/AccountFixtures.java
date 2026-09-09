package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Module;
import com.bloxbean.cardano.kavach.auth.browser.BrowserModule;
import com.bloxbean.cardano.kavach.auth.policy.PolicyModule;
import com.bloxbean.cardano.kavach.auth.policy.MixedSetupModule;
import com.bloxbean.cardano.kavach.auth.policy.BudgetPolicyModule;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Configuration;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Budget;
import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Disposable compiled-script fixtures; none of these keys or contexts represent ledger authority.
 */
final class AccountFixtures {
    final List<KeyPair> keys = new ArrayList<>();
    final DeploymentDomain deployment = new DeploymentDomain(BigInteger.ZERO, BigInteger.valueOf(42), new byte[32]);
    final Address sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty());
    final TxOutRef seed = ref(10);
    final TxOutRef stateRef;
    final TxOutRef assetRef = ref(30);
    final PlutusV3Script stateScript;
    final PlutusV3Script coreScript;
    final PlutusV3Script moduleScript;
    final PlutusV3Script nftScript;
    final PlutusV3Script assetScript;
    final PlutusV3Script budgetScript;
    final AccountId budgetId = new AccountId(new byte[28], new byte[0]);
    final AccountState state;
    final Ed25519Config config;
    final Credential core;
    final Credential module;
    final PlutusV3Script finalModuleScript;
    final Address accountAddress;

    AccountFixtures() throws Exception {
        this(false);
    }

    /**
     * Maximum registry and broad policies, retaining independent cancellation and unfreeze.
     */
    AccountFixtures(boolean maximum) throws Exception {
        this(maximum, 0);
    }

    /**
     * Uses a separately applied browser module without changing any immutable validator.
     */
    AccountFixtures(boolean maximum, int browserMode) throws Exception {
        this(maximum, browserMode, BigInteger.ONE);
    }

    AccountFixtures(boolean maximum, int browserMode, BigInteger budgetPeriod) throws Exception {
        this(maximum, browserMode, budgetPeriod, false);
    }

    AccountFixtures(boolean maximum, int browserMode, BigInteger budgetPeriod, boolean mixedSetup) throws Exception {
        var lateHash = new byte[32];
        lateHash[31] = (byte) 255;
        stateRef = maximum ? new TxOutRef(new TxId(lateHash), BigInteger.ZERO) : ref(20);
        for (int i = 0; i < (maximum ? 16 : 3); i++)
            keys.add(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        stateScript = load(AccountStateValidator.class, BigInteger.ONE, deployment);
        coreScript = load(CoreCheckpoint.class, BigInteger.ONE, deployment, stateScript.getScriptHash(), sink);
        finalModuleScript = browserMode == 0 ? load(Ed25519Module.class, BigInteger.ONE, BigInteger.ONE, deployment, stateScript.getScriptHash(), coreScript.getScriptHash(), sink)
                : browserMode >= 3 ? load(browserMode == 4 ? BudgetPolicyModule.class : PolicyModule.class, BigInteger.ONE, BigInteger.ONE, deployment, stateScript.getScriptHash(), coreScript.getScriptHash(), sink)
                : load(BrowserModule.class, BigInteger.ONE, BigInteger.ONE, deployment, stateScript.getScriptHash(), coreScript.getScriptHash(), sink, BigInteger.valueOf(browserMode));
        moduleScript = mixedSetup ? load(MixedSetupModule.class, BigInteger.ONE, BigInteger.ONE, deployment,
                stateScript.getScriptHash(), coreScript.getScriptHash(), sink, finalModuleScript.getScriptHash()) : finalModuleScript;
        nftScript = load(StateNftPolicy.class, BigInteger.ONE, deployment, seed, new byte[28], stateScript.getScriptHash());
        var id = new AccountId(nftScript.getScriptHash(), new byte[]{});
        budgetScript = load(PeriodicBudgetValidator.class, BigInteger.ONE, deployment, id, stateScript.getScriptHash(), coreScript.getScriptHash());
        assetScript = browserMode == 4 ? load(BudgetAccountAssetValidator.class, BigInteger.ONE, deployment, id,
                stateScript.getScriptHash(), coreScript.getScriptHash(), budgetScript.getScriptHash())
                : load(AccountAssetValidator.class, BigInteger.ONE, deployment, id, stateScript.getScriptHash(), coreScript.getScriptHash());
        var registry = new ArrayList<KeyEntry>();
        for (int keyId = 0; keyId < keys.size(); keyId++)
            registry.add(new KeyEntry(BigInteger.valueOf(keyId), publicKey(keys.get(keyId))));
        config = maximum ? new Ed25519Config(BigInteger.ONE, list(registry.toArray(KeyEntry[]::new)),
                policy(8, 0, 1, 2, 3, 4, 5, 6, 7), policy(8, 8, 9, 10, 11, 12, 13, 14, 15), policy(8, 8, 9, 10, 11, 12, 13, 14, 15),
                policy(8, 8, 9, 10, 11, 12, 13, 14, 15), policy(8, 0, 1, 2, 3, 4, 5, 6, 7), policy(8, 8, 9, 10, 11, 12, 13, 14, 15))
                : new Ed25519Config(BigInteger.ONE, list(new KeyEntry(BigInteger.ZERO, publicKey(keys.get(0))),
                new KeyEntry(BigInteger.ONE, publicKey(keys.get(1))), new KeyEntry(BigInteger.TWO, publicKey(keys.get(2)))),
                browserMode >= 3 ? policy(2, 0, 1) : policy(1, 0),
                browserMode >= 3 ? policy(2, 0, 2) : policy(2, 0, 1),
                policy(1, 1), policy(1, 2), policy(1, 1), policy(1, 2));
        var authorization = AccountCodec.data(browserMode >= 3 ? new PolicyConfig(BigInteger.ONE, config,
                list(BigInteger.ONE), BigInteger.valueOf(2000000), maximum ? config.spend() : policy(1, 0)) : config);
        var configuration = browserMode == 4 ? AccountCodec.data(new Configuration(BigInteger.ONE,
                Optional.of(new Budget(budgetId, budgetPeriod, BigInteger.valueOf(5000000))), authorization)) : authorization;
        state = new AccountState(BigInteger.ONE, id, deployment, new CoreBinding(stateScript.getScriptHash(), assetScript.getScriptHash(), coreScript.getScriptHash()),
                BigInteger.ZERO, new AuthModuleRef(moduleScript.getScriptHash(), BigInteger.ONE),
                configuration, BigInteger.ZERO,
                BigInteger.valueOf(86400000), BigInteger.valueOf(3600000), BigInteger.ZERO, new Normal());
        WireFormat.validateState(AccountCodec.data(state));
        core = new Credential.ScriptCredential(new ScriptHash(coreScript.getScriptHash()));
        module = new Credential.ScriptCredential(new ScriptHash(moduleScript.getScriptHash()));
        accountAddress = new Address(new Credential.ScriptCredential(new ScriptHash(assetScript.getScriptHash())), Optional.empty());
    }

    static PlutusV3Script load(Class<?> type, Object... args) {
        var data = Arrays.stream(args).map(AccountCodec::data).map(PlutusDataAdapter::toClientLib)
                .toArray(com.bloxbean.cardano.client.plutus.spec.PlutusData[]::new);
        return JulcScriptLoader.load(type, data);
    }

    @SafeVarargs
    static <T> JulcList<T> list(T... entries) {
        JulcList<T> result = JulcList.empty();
        for (int i = entries.length - 1; i >= 0; i--) result = result.prepend(entries[i]);
        return result;
    }

    static ThresholdPolicy policy(int threshold, int... ids) {
        return new ThresholdPolicy(BigInteger.valueOf(threshold), list(Arrays.stream(ids).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new)));
    }

    static TxOutRef ref(int index) {
        return new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(index));
    }

    static byte[] publicKey(KeyPair pair) {
        var bytes = pair.getPublic().getEncoded();
        return Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
    }

    static Signature sign(int id, KeyPair pair, byte[] digest) throws Exception {
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(digest);
        return new Signature(BigInteger.valueOf(id), signer.sign());
    }

    IntentEnvelope spend(long accountFee) {
        return new IntentEnvelope(WireFormat.protocolTag(), new IntentDomain(BigInteger.ONE, deployment, state.accountId(), state.coreBinding(), BigInteger.ZERO, stateRef),
                new Validity(BigInteger.valueOf(1000), BigInteger.valueOf(10000)),
                new Spend(list(assetRef), list(new Recipient(BigInteger.ZERO, sink, list(new Asset(new byte[]{}, new byte[]{}, BigInteger.valueOf(2000000))))), BigInteger.valueOf(accountFee)));
    }

    ModuleRedeemer authorization(IntentEnvelope intent) throws Exception {
        return new ModuleRedeemer(BigInteger.ONE, intent, Optional.of(new Proof(BigInteger.ZERO,
                list(sign(0, keys.get(0), WireFormat.digest(AccountCodec.data(intent)))))), JulcList.empty(), JulcList.empty());
    }

    TxInInfo stateInput() {
        return new TxInInfo(stateRef, new TxOut(new Address(new Credential.ScriptCredential(new ScriptHash(state.coreBinding().stateValidator())), Optional.empty()),
                Value.lovelace(BigInteger.valueOf(10000000)).merge(Value.singleton(new PolicyId(state.accountId().policy()), TokenName.EMPTY, BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(AccountCodec.data(state)), Optional.empty()));
    }

    static TxOut output(Address address, long ada) {
        return new TxOut(address, Value.lovelace(BigInteger.valueOf(ada)), new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    ScriptContextTestBuilder context(String role, IntentEnvelope intent, ModuleRedeemer auth) {
        var coreRedeemer = new CoreRedeemer(BigInteger.ONE, intent, auth.receipts());
        var assetRedeemer = new AssetRedeemer(BigInteger.ONE, WireFormat.digest(AccountCodec.data(intent)));
        var builder = role.equals("asset") ? ScriptContextTestBuilder.spending(assetRef) : ScriptContextTestBuilder.rewarding(role.equals("core") ? core : module);
        return builder.redeemer(AccountCodec.data(role.equals("core") ? coreRedeemer : role.equals("asset") ? assetRedeemer : auth))
                .input(new TxInInfo(assetRef, output(accountAddress, 10000000)))
                .referenceInput(stateInput()).output(output(sink, 2000000)).output(output(accountAddress, 8000000))
                .fee(BigInteger.valueOf(200000)).validRange(Interval.between(BigInteger.valueOf(1000), BigInteger.valueOf(9999)))
                .withdrawal(core, BigInteger.ZERO).withdrawal(module, BigInteger.ZERO)
                .redeemerEntry(new ScriptPurpose.Rewarding(core), AccountCodec.data(coreRedeemer))
                .redeemerEntry(new ScriptPurpose.Rewarding(module), AccountCodec.data(auth))
                .redeemerEntry(new ScriptPurpose.Spending(assetRef), AccountCodec.data(assetRedeemer));
    }

    /**
     * Canonical ledger input order; maximum fixtures place the authenticated state last.
     */
    static PlutusData orderedInputs(PlutusData context) {
        var tx = (PlutusData.ConstrData) ((PlutusData.ConstrData) context).fields().get(0);
        var inputs = new ArrayList<>(((PlutusData.ListData) tx.fields().get(0)).items());
        inputs.sort((a, b) -> {
            var left = ((PlutusData.ConstrData) ((PlutusData.ConstrData) a).fields().get(0)).fields();
            var right = ((PlutusData.ConstrData) ((PlutusData.ConstrData) b).fields().get(0)).fields();
            int order = Arrays.compareUnsigned(((PlutusData.BytesData) left.get(0)).value(), ((PlutusData.BytesData) right.get(0)).value());
            return order != 0 ? order : ((PlutusData.IntData) left.get(1)).value().compareTo(((PlutusData.IntData) right.get(1)).value());
        });
        return AccountAdversarialTest.field(context, new PlutusData.ListData(inputs), 0, 0);
    }

    EvalResult evaluate(String role, ScriptContextTestBuilder context) {
        return evaluate(role, context.buildPlutusData());
    }

    EvalResult evaluate(String role, PlutusData context) {
        var script = role.equals("budget") ? budgetScript : role.equals("core") ? coreScript : role.equals("asset") ? assetScript : role.equals("nft") ? nftScript : role.equals("state") ? stateScript : moduleScript;
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(context), new ExBudget(10000000000L, 16500000), EvalOptions.DEFAULT);
    }
}
