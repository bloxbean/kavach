package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial composed-transfer contexts; signatures are regenerated where attackers have spend authority. */
class AccountAdversarialTest {
    private final AccountFixtures f = new AccountFixtures();
    AccountAdversarialTest() throws Exception {}

    /** Replaces a record/list field while preserving all other data, including deliberately malformed values. */
    static PlutusData field(PlutusData data, PlutusData replacement, int... path) {
        if (path.length == 0) return replacement;
        var values = new ArrayList<>(data instanceof PlutusData.ConstrData c ? c.fields() : ((PlutusData.ListData)data).items());
        values.set(path[0], field(values.get(path[0]), replacement, Arrays.copyOfRange(path, 1, path.length)));
        return data instanceof PlutusData.ConstrData c ? new PlutusData.ConstrData(c.tag(), values) : new PlutusData.ListData(values);
    }
    /** Rewrites shared ABI data consistently; unlike an invalid signature this tests immutable semantics. */
    static PlutusData replace(PlutusData data, PlutusData before, PlutusData after) {
        if (data.equals(before)) return after;
        return switch (data) {
            case PlutusData.ConstrData c -> new PlutusData.ConstrData(c.tag(), c.fields().stream().map(d -> replace(d, before, after)).toList());
            case PlutusData.ListData l -> new PlutusData.ListData(l.items().stream().map(d -> replace(d, before, after)).toList());
            case PlutusData.MapData m -> new PlutusData.MapData(m.entries().stream().map(e -> new PlutusData.Pair(replace(e.key(), before, after), replace(e.value(), before, after))).toList());
            default -> data;
        };
    }
    @ParameterizedTest @ValueSource(strings={"recipient-amount", "recipient-address", "change-address", "change-stake", "change-datum", "fee-theft", "unexpected-token", "missing-account-input", "extra-account-input", "other-script-input", "recipient-reuse"})
    void immutableAnchorRejectsValueAndReplayAttacks(String attack) throws Exception {
        var intent = f.spend(0); var auth = f.authorization(intent);
        var builder = f.context("asset", intent, auth); var context = builder.buildPlutusData();
        var keyAddress = new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.of(new StakingCredential.StakingHash(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])))));
        context = switch (attack) {
            case "recipient-amount" -> field(context, Value.lovelace(BigInteger.valueOf(1999999)).toPlutusData(), 0, 2, 0, 1);
            case "recipient-address" -> field(context, keyAddress.toPlutusData(), 0, 2, 0, 0);
            case "change-address" -> field(context, f.sink.toPlutusData(), 0, 2, 1, 0);
            case "change-stake" -> field(context, new Address(f.accountAddress.credential(), keyAddress.stakingCredential()).toPlutusData(), 0, 2, 1, 0);
            case "change-datum" -> field(context, new OutputDatum.OutputDatumInline(PlutusData.integer(0)).toPlutusData(), 0, 2, 1, 2);
            case "fee-theft" -> field(context, Value.lovelace(BigInteger.valueOf(7999999)).toPlutusData(), 0, 2, 1, 1);
            case "unexpected-token" -> field(context, Value.lovelace(BigInteger.valueOf(8000000)).merge(Value.singleton(new PolicyId(new byte[28]), TokenName.EMPTY, BigInteger.ONE)).toPlutusData(), 0, 2, 1, 1);
            case "missing-account-input" -> field(context, new PlutusData.ListData(List.of()), 0, 0);
            case "extra-account-input" -> builder.input(new TxInInfo(AccountFixtures.ref(31), AccountFixtures.output(f.accountAddress, 1))).buildPlutusData();
            case "other-script-input" -> builder.input(new TxInInfo(AccountFixtures.ref(31), AccountFixtures.output(new Address(f.core, Optional.empty()), 1))).buildPlutusData();
            case "recipient-reuse" -> {
                var spend = (Spend)intent.action();
                var changed = new IntentEnvelope(intent.protocolTag(), intent.domain(), intent.validity(), new Spend(spend.accountInputs(), AccountFixtures.list(spend.recipients().head(), spend.recipients().head()), BigInteger.ZERO));
                yield f.context("asset", changed, f.authorization(changed)).buildPlutusData();
            }
            default -> throw new AssertionError(attack);
        };
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", context), attack);
    }
    @ParameterizedTest @ValueSource(strings={"deployment", "account", "version", "state-ref", "protocol", "expired", "wide", "operation", "absent-anchor"})
    void checkpointRejectsSignedForeignOrUnsupportedIntent(String attack) throws Exception {
        var original = f.spend(0); var d = original.domain();
        var deployment = attack.equals("deployment") ? new DeploymentDomain(BigInteger.ZERO, BigInteger.valueOf(43), new byte[32]) : d.deploymentDomain();
        var domain = new IntentDomain(BigInteger.ONE, deployment, attack.equals("account") ? new AccountId(new byte[28], new byte[]{}) : d.accountId(),
                d.coreBinding(), attack.equals("version") ? BigInteger.ONE : BigInteger.ZERO, attack.equals("state-ref") ? AccountFixtures.ref(999) : d.stateRef());
        var interval = attack.equals("expired") ? new Validity(BigInteger.ZERO, BigInteger.valueOf(500)) : attack.equals("wide") ? new Validity(BigInteger.ZERO, BigInteger.valueOf(300001)) : original.validity();
        Action action = attack.equals("operation") ? new Freeze() : attack.equals("absent-anchor") ? new Spend(AccountFixtures.list(AccountFixtures.ref(999)), JulcList.empty(), BigInteger.ZERO) : original.action();
        var altered = new IntentEnvelope(attack.equals("protocol") ? new byte[14] : original.protocolTag(), domain, interval, action);
        var result = f.evaluate("core", f.context("core", altered, f.authorization(altered)));
        assertInstanceOf(EvalResult.Failure.class, result, attack);
    }
    @ParameterizedTest @ValueSource(strings={"missing-nft", "wrong-policy", "wrong-state-address", "frozen", "state-extra-field", "state-wrong-tag", "module-hash"})
    void authenticatedStateRejectsLookalikes(String attack) throws Exception {
        var intent = f.spend(0); var auth = f.authorization(intent);
        for (var role : List.of("core", "module")) {
            var context = f.context(role, intent, auth).buildPlutusData();
            PlutusData state = AccountCodec.data(f.state);
            context = switch (attack) {
                case "missing-nft" -> field(context, Value.lovelace(BigInteger.valueOf(10000000)).toPlutusData(), 0, 1, 0, 1, 1);
                case "wrong-policy" -> field(context, Value.lovelace(BigInteger.valueOf(10000000)).merge(Value.singleton(new PolicyId(new byte[28]), TokenName.EMPTY, BigInteger.ONE)).toPlutusData(), 0, 1, 0, 1, 1);
                case "wrong-state-address" -> field(context, f.sink.toPlutusData(), 0, 1, 0, 1, 0);
                case "frozen" -> field(context, field(state, PlutusData.constr(1), 11), 0, 1, 0, 1, 2, 0);
                case "state-extra-field" -> { var fields = new ArrayList<>(((PlutusData.ConstrData)state).fields()); fields.add(PlutusData.integer(0)); yield field(context, new PlutusData.ConstrData(0, fields), 0, 1, 0, 1, 2, 0); }
                case "state-wrong-tag" -> field(context, new PlutusData.ConstrData(1, ((PlutusData.ConstrData)state).fields()), 0, 1, 0, 1, 2, 0);
                case "module-hash" -> field(context, field(state, PlutusData.bytes(new byte[28]), 5, 0), 0, 1, 0, 1, 2, 0);
                default -> throw new AssertionError(attack);
            };
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, context), role + " " + attack);
        }
    }
    @Test void moduleRejectsWrongKeyDuplicateProofsAndWrongScheme() throws Exception {
        var intent = f.spend(0); var good = f.authorization(intent);
        var wrong = AccountFixtures.sign(0, f.keys.get(1), WireFormat.digest(AccountCodec.data(intent)));
        for (var proof : List.of(new Proof(BigInteger.ZERO, AccountFixtures.list(wrong)), new Proof(BigInteger.ONE, good.operationProof().get().signatures()),
                new Proof(BigInteger.ZERO, AccountFixtures.list(good.operationProof().get().signatures().head(), good.operationProof().get().signatures().head())))) {
            var auth = new ModuleRedeemer(BigInteger.ONE, intent, Optional.of(proof), JulcList.empty(), JulcList.empty());
            assertInstanceOf(EvalResult.Failure.class, f.evaluate("module", f.context("module", intent, auth)));
        }
    }
    @Test void missingOrWrongCheckpointCannotAuthorizeAnAssetInput() throws Exception {
        var intent = f.spend(0); var auth = f.authorization(intent);
        var ctx = f.context("asset", intent, auth).buildPlutusData();
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", field(ctx, new PlutusData.MapData(List.of()), 0, 6)));
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", field(ctx, new PlutusData.MapData(List.of()), 0, 9)));
        var foreign = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", replace(ctx, f.core.toPlutusData(), foreign.toPlutusData())));
        var wrongDigest = field(ctx, PlutusData.bytes(new byte[32]), 1, 1);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", wrongDigest));
    }
    @Test void anchorMustActuallyBeLockedByTheAuthenticatedAssetScript() throws Exception {
        var intent = f.spend(0);
        for (var address : List.of(f.sink, new Address(f.module, Optional.empty()))) {
            var ctx = f.context("core", intent, f.authorization(intent)).buildPlutusData();
            ctx = field(ctx, address.toPlutusData(), 0, 0, 0, 1, 0);
            assertInstanceOf(EvalResult.Failure.class, f.evaluate("core", ctx));
        }
    }
    /** Bypasses SDK shape guards to model an attacker signing deliberately invalid wire Data. */
    private static byte[] attackerDigest(PlutusData data) {
        byte[] encoded = Builtins.serialiseData(data);
        var hash = new Blake2bDigest(256); hash.update(encoded, 0, encoded.length);
        byte[] digest = new byte[32]; hash.doFinal(digest, 0); return digest;
    }
    /** Maintains cryptographically valid evidence for malformed signed bytes to test their owning shape checks. */
    private PlutusData signedMalformedContext(String role, PlutusData malformed) throws Exception {
        var good = f.spend(0); var auth = f.authorization(good);
        var ctx = f.context(role, good, auth).buildPlutusData();
        var digest = attackerDigest(malformed);
        ctx = replace(ctx, AccountCodec.data(good), malformed);
        ctx = replace(ctx, PlutusData.bytes(WireFormat.digest(AccountCodec.data(good))), PlutusData.bytes(digest));
        return replace(ctx, AccountCodec.data(auth.operationProof().get().signatures().head()), AccountCodec.data(AccountFixtures.sign(0, f.keys.get(0), digest)));
    }
    @ParameterizedTest @ValueSource(strings={"envelope", "domain", "validity", "spend", "recipient", "asset"})
    void malformedSignedRecordsRejectAtTheirOwningImmutableBoundary(String target) throws Exception {
        var original = AccountCodec.data(f.spend(0));
        int[] path = switch (target) {
            case "envelope" -> new int[]{};
            case "domain" -> new int[]{1};
            case "validity" -> new int[]{2};
            case "spend" -> new int[]{3};
            case "recipient" -> new int[]{3, 1, 0};
            case "asset" -> new int[]{3, 1, 0, 2, 0};
            default -> throw new AssertionError(target);
        };
        PlutusData selected = original;
        for (int index : path) selected = selected instanceof PlutusData.ConstrData c ? c.fields().get(index) : ((PlutusData.ListData)selected).items().get(index);
        var record = (PlutusData.ConstrData)selected;
        var fields = new ArrayList<>(record.fields()); fields.add(PlutusData.integer(0));
        String role = target.equals("spend") || target.equals("recipient") || target.equals("asset") ? "asset" : "core";
        var missing = new ArrayList<>(record.fields()); missing.removeLast();
        for (var bad : List.of(new PlutusData.ConstrData(record.tag(), fields), new PlutusData.ConstrData(99, record.fields()),
                new PlutusData.ConstrData(record.tag(), missing), PlutusData.integer(0), PlutusData.bytes(new byte[0]),
                new PlutusData.MapData(List.of()), new PlutusData.ListData(List.of()))) {
            var malformed = field(original, bad, path);
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, signedMalformedContext(role, malformed)), target);
        }
    }
    @Test void immutableStateCannotBeSpent() throws Exception {
        var intent = f.spend(0);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("state", f.context("asset", intent, f.authorization(intent))));
    }
}
