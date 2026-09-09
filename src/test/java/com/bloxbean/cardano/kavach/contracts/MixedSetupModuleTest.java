package com.bloxbean.cardano.kavach.contracts;

import static org.junit.jupiter.api.Assertions.*;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.IntStream;
import com.bloxbean.cardano.kavach.protocol.PolicyConfigCodec;
import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compiled restricted setup checks; live composition is covered by the demo integration gate. */
class MixedSetupModuleTest {
    static JulcList<Signature> proofs(AccountFixtures f, byte[] digest, int... ids) throws Exception {
        var values = new ArrayList<Signature>();
        for (int id : ids) values.add(id == 1 ? BrowserModuleTest.proofs(f, 2, digest, id).head() : new Signature(BigInteger.valueOf(id), new byte[0]));
        return AccountFixtures.list(values.toArray(Signature[]::new));
    }
    @ParameterizedTest
    @ValueSource(strings = {"valid", "maximum", "missing-key", "missing-witness", "wrong-domain", "duplicate", "wrong-method", "wrong-nft", "wrong-custody"})
    void genesis(String attack) throws Exception {
        var f = new AccountFixtures(attack.equals("maximum"), 3, BigInteger.ONE, true);
        var digest = WireFormat.digest(ProofDomains.genesis(AccountCodec.data(f.state)));
        if (attack.equals("wrong-domain")) digest[0] ^= 1;
        int count = f.keys.size() - (attack.equals("missing-key") ? 1 : 0);
        var proof = proofs(f, digest, IntStream.range(0, count).toArray());
        if (attack.equals("duplicate")) proof = AccountFixtures.list(proof.head(), proof.head(), proof.tail().tail().head());
        if (attack.equals("wrong-method")) proof = AccountFixtures.list(new Signature(BigInteger.ZERO, new byte[0]), new Signature(BigInteger.ONE, new byte[0]), new Signature(BigInteger.TWO, new byte[0]));
        var genesis = new GenesisModuleRedeemer(BigInteger.ONE, f.state, proof, JulcList.empty());
        var stateOutput = f.stateInput().resolved();
        if (attack.equals("wrong-custody")) stateOutput = new TxOut(f.sink, stateOutput.value(), stateOutput.datum(), Optional.empty());
        var ctx = ScriptContextTestBuilder.rewarding(f.module).redeemer(AccountCodec.data(genesis))
                .input(new TxInInfo(f.seed, AccountFixtures.output(f.sink, 20000000)))
                .output(stateOutput).mint(Value.singleton(new PolicyId(attack.equals("wrong-nft") ? new byte[28] : f.state.accountId().policy()), TokenName.EMPTY, BigInteger.ONE))
                .withdrawal(f.module, BigInteger.ZERO).redeemerEntry(new ScriptPurpose.Rewarding(f.module), AccountCodec.data(genesis));
        for (int id = 0; id < f.keys.size(); id++) if (!(id == 2 && attack.equals("missing-witness"))) ctx.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(id))));
        var result = f.evaluate("module", ctx);
        if (attack.equals("valid") || attack.equals("maximum")) assertInstanceOf(EvalResult.Success.class, result, result.toString());
        else assertInstanceOf(EvalResult.Failure.class, result, attack);
    }
    @ParameterizedTest
    @ValueSource(strings = {"valid", "wrong-target", "changed-config", "missing-admin", "missing-witness", "spend"})
    void onlyUnchangedPrecommittedActivation(String attack) throws Exception {
        var f = new AccountFixtures(false, 3, BigInteger.ONE, true);
        var life = new AccountLifecycleTest(); life.f = f;
        var target = new AuthModuleRef(attack.equals("wrong-target") ? new byte[28] : f.finalModuleScript.getScriptHash(), BigInteger.ONE);
        var config = f.state.authConfig();
        if (attack.equals("changed-config")) {
            var old = PolicyConfigCodec.decode(config);
            config = AccountCodec.data(new PolicyConfig(BigInteger.ONE, old.roles(), old.coseIds(), old.smallPaymentLimit().add(BigInteger.ONE), old.smallSpend()));
        }
        var action = new ReplaceModule(target, config);
        var intent = life.intent(f.state, action, 1000, 9999);
        if (attack.equals("spend")) intent = f.spend(0);
        var auth = new ModuleRedeemer(BigInteger.ONE, intent, Optional.of(new Proof(BigInteger.valueOf(3), proofs(f, AccountCodec.intentDigest(intent, null), attack.equals("missing-admin") ? new int[]{0} : new int[]{0, 2}))), JulcList.empty(), JulcList.empty());
        ScriptContextTestBuilder ctx;
        if (attack.equals("spend")) ctx = f.context("module", intent, auth);
        else {
            var next = AccountAdministration.successor(f.state, intent, BigInteger.valueOf(1000), BigInteger.valueOf(9999), true);
            ctx = life.context("module", f.state, next, auth, 1000, 9999).withdrawal(new Credential.ScriptCredential(new ScriptHash(target.scriptHash())), BigInteger.ZERO);
        }
        ctx.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(0))));
        if (!attack.equals("missing-witness")) ctx.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(2))));
        var result = f.evaluate("module", ctx);
        if (attack.equals("valid")) assertInstanceOf(EvalResult.Success.class, result, result.toString());
        else assertInstanceOf(EvalResult.Failure.class, result, attack);
    }
}
