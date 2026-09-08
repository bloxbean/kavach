package com.bloxbean.cardano.kavach.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.julc.core.types.JulcList;
import java.security.interfaces.EdECPrivateKey;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Complete immutable composition with separately applied browser modules; not real-wallet evidence.
 */
class BrowserModuleTest {
    @ParameterizedTest
    @CsvSource({
        "1,freeze",
        "2,freeze",
        "1,unfreeze",
        "2,unfreeze",
        "1,config",
        "2,config",
        "1,start",
        "2,start",
        "1,cancel",
        "2,cancel",
        "1,complete",
        "2,complete"
    })
    void browserLifecyclePreservesImmutableBindings(int mode, String operation) throws Exception {
        var life = new AccountLifecycleTest();
        life.f = new AccountFixtures(false, mode);
        var f = life.f;
        long lower = 86400000, upper = lower + 99;
        var old = f.state;
        Action action = new Freeze();
        int[] ids = {1};
        switch (operation) {
            case "unfreeze" -> {
                old = life.state(old, 1, 0, 0, new Frozen());
                action = new Unfreeze();
                ids = new int[] {2};
            }
            case "config" -> {
                action = new ReplaceConfig(old.authConfig());
                ids = new int[] {0, 1};
            }
            case "start" -> action = new StartRecovery(BigInteger.ONE, old.authConfig());
            case "cancel", "complete" -> {
                old =
                        life.state(
                                old,
                                1,
                                1,
                                3600000,
                                new RecoveryPending(
                                        new byte[32], BigInteger.valueOf(lower), old.authConfig()));
                if (operation.equals("cancel")) {
                    action = new CancelRecovery(BigInteger.ONE, new byte[32]);
                    ids = new int[] {2};
                } else {
                    action = new CompleteRecovery(BigInteger.ONE, old.authConfig());
                    ids = new int[] {0};
                }
            }
        }
        var intent = life.intent(old, action, lower, upper);
        var next =
                AccountAdministration.successor(
                        old, intent, BigInteger.valueOf(lower), BigInteger.valueOf(upper), true);
        byte[] message =
                operation.equals("complete")
                        ? WireFormat.digest(
                                ProofDomains.target(
                                        AccountCodec.data(old), AccountCodec.data(intent)))
                        : AccountCodec.intentDigest(intent, null);
        var proofs = proofs(f, mode, message, ids);
        var auth =
                new ModuleRedeemer(
                        BigInteger.ONE,
                        intent,
                        operation.equals("complete")
                                ? Optional.empty()
                                : Optional.of(new Proof(BigInteger.valueOf(mode), proofs)),
                        operation.equals("complete") ? proofs : JulcList.empty(),
                        JulcList.empty());
        for (String role : List.of("state", "core", "module")) {
            var ctx = life.context(role, old, next, auth, lower, upper);
            if (mode == 1)
                for (int id : ids)
                    ctx.signer(
                            BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(id))));
            assertInstanceOf(
                    EvalResult.Success.class,
                    f.evaluate(role, ctx),
                    mode + " " + operation + " " + role);
        }
        int wrongId =
                operation.equals("unfreeze")
                                || operation.equals("cancel")
                                || operation.equals("complete")
                        ? 1
                        : 0;
        var wrongProofs = proofs(f, mode, message, wrongId);
        var wrongAuth =
                new ModuleRedeemer(
                        BigInteger.ONE,
                        intent,
                        operation.equals("complete")
                                ? Optional.empty()
                                : Optional.of(new Proof(BigInteger.valueOf(mode), wrongProofs)),
                        operation.equals("complete") ? wrongProofs : JulcList.empty(),
                        JulcList.empty());
        var wrongContext = life.context("module", old, next, wrongAuth, lower, upper);
        if (mode == 1)
            wrongContext.signer(
                    BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(wrongId))));
        assertInstanceOf(
                EvalResult.Failure.class,
                f.evaluate("module", wrongContext),
                "Wrong or insufficient lifecycle authority must reject");
    }

    @ParameterizedTest
    @CsvSource({
        "1,valid",
        "2,valid",
        "2,valid-legacy",
        "2,valid-base",
        "2,marker",
        "2,strip-kid",
        "1,missing",
        "2,signature",
        "1,wrong-signer",
        "2,network",
        "2,payload",
        "1,wrong-mode",
        "2,wrong-mode",
        "1,duplicate",
        "2,duplicate",
        "1,extra-bytes",
        "2,extra-bytes"
    })
    void transferProofsRejectInvalidEvidence(int mode, String attack) throws Exception {
        var f = new AccountFixtures(false, mode);
        var intent = f.spend(0);
        byte[] digest = AccountCodec.intentDigest(intent, null);
        if (attack.equals("payload")) digest[0] ^= 1;
        var signatures = proofs(f, mode, digest, !attack.equals("valid-legacy"), attack.equals("valid-base") ? 0 : 6, new int[] {0});
        if (attack.equals("strip-kid")) {
            var bytes = signatures.head().signature();
            signatures = AccountFixtures.list(new Signature(BigInteger.ZERO, Arrays.copyOf(bytes, bytes.length - 1)));
        }
        if (attack.equals("marker") || attack.equals("signature")
                || attack.equals("network")
                || attack.equals("extra-bytes")) {
            var bytes = signatures.head().signature().clone();
            if (attack.equals("extra-bytes")) bytes = Arrays.copyOf(bytes, bytes.length + 1);
            else bytes[attack.equals("network") ? 0 : attack.equals("signature") ? bytes.length - 2 : bytes.length - 1] ^= 1;
            signatures = AccountFixtures.list(new Signature(BigInteger.ZERO, bytes));
        }
        if (attack.equals("duplicate"))
            signatures = AccountFixtures.list(signatures.head(), signatures.head());
        var auth =
                new ModuleRedeemer(
                        BigInteger.ONE,
                        intent,
                        Optional.of(
                                new Proof(
                                        BigInteger.valueOf(attack.equals("wrong-mode") ? 0 : mode),
                                        signatures)),
                        JulcList.empty(),
                        JulcList.empty());
        var ctx = f.context("module", intent, auth);
        if (mode == 1 && !attack.equals("missing"))
            ctx.signer(
                    BrowserSignatures.keyHash(
                            AccountFixtures.publicKey(
                                    f.keys.get(attack.equals("wrong-signer") ? 1 : 0))));
        var result = f.evaluate("module", ctx);
        if (attack.startsWith("valid"))
            assertInstanceOf(EvalResult.Success.class, result, result.toString());
        else assertInstanceOf(EvalResult.Failure.class, result, attack);
    }

    @ParameterizedTest
    @CsvSource({
        "1,valid",
        "2,valid",
        "1,missing-key",
        "2,missing-key",
        "1,missing-witness",
        "2,wrong-domain"
    })
    void genesisRequiresEveryEnrolledKey(int mode, String attack) throws Exception {
        var f = new AccountFixtures(false, mode);
        var digest = WireFormat.digest(ProofDomains.genesis(AccountCodec.data(f.state)));
        if (attack.equals("wrong-domain")) digest[0] ^= 1;
        var evidence =
                attack.equals("missing-key")
                        ? proofs(f, mode, digest, 0, 1)
                        : proofs(f, mode, digest, 0, 1, 2);
        var genesis =
                new GenesisModuleRedeemer(BigInteger.ONE, f.state, evidence, JulcList.empty());
        var ctx =
                ScriptContextTestBuilder.rewarding(f.module)
                        .redeemer(AccountCodec.data(genesis))
                        .input(new TxInInfo(f.seed, AccountFixtures.output(f.sink, 20000000)))
                        .signer(new byte[28])
                        .output(f.stateInput().resolved())
                        .mint(
                                Value.singleton(
                                        new PolicyId(f.state.accountId().policy()),
                                        TokenName.EMPTY,
                                        BigInteger.ONE))
                        .withdrawal(f.module, BigInteger.ZERO)
                        .redeemerEntry(
                                new ScriptPurpose.Rewarding(f.module), AccountCodec.data(genesis));
        if (mode == 1)
            for (int id = 0; id < (attack.equals("missing-witness") ? 2 : 3); id++)
                ctx.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(id))));
        var result = f.evaluate("module", ctx);
        if (attack.equals("valid")) assertInstanceOf(EvalResult.Success.class, result);
        else assertInstanceOf(EvalResult.Failure.class, result);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void fullRegistryEightApproverTransferFitsBudget(int mode) throws Exception {
        var f = new AccountFixtures(true, mode);
        var intent = f.spend(0);
        var auth =
                new ModuleRedeemer(
                        BigInteger.ONE,
                        intent,
                        Optional.of(
                                new Proof(
                                        BigInteger.valueOf(mode),
                                        proofs(
                                                f,
                                                mode,
                                                AccountCodec.intentDigest(intent, null),
                                                0,
                                                1,
                                                2,
                                                3,
                                                4,
                                                5,
                                                6,
                                                7))),
                        JulcList.empty(),
                        JulcList.empty());
        long memory = 0, cpu = 0;
        for (var role : List.of("asset", "core", "module")) {
            var ctx = f.context(role, intent, auth);
            if (mode == 1)
                for (int id = 0; id < 8; id++)
                    ctx.signer(
                            BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(id))));
            var result = assertInstanceOf(EvalResult.Success.class, f.evaluate(role, ctx));
            memory += result.consumed().memoryUnits();
            cpu += result.consumed().cpuSteps();
        }
        System.out.println(
                "Browser mode "
                        + mode
                        + " full registry / eight-approver one-input transfer: memory="
                        + memory
                        + " cpu="
                        + cpu);
        assertTrue(memory * 1.05 < 16500000);
        assertTrue(cpu * 1.05 < 10000000000L);
    }

    static JulcList<Signature> proofs(AccountFixtures fixture, int mode, byte[] message, int... ids)
            throws Exception {
        return proofs(fixture, mode, message, true, 6, ids);
    }

    private static JulcList<Signature> proofs(AccountFixtures fixture, int mode, byte[] message,
            boolean withKid, int addressType, int... ids) throws Exception {
        var evidence = new ArrayList<Signature>();
        for (int id : ids) {
            byte[] packed = new byte[0];
            if (mode == 2) {
                byte[] address = new byte[addressType == 6 ? 29 : 57];
                address[0] = (byte) (addressType * 16);
                System.arraycopy(
                        BrowserSignatures.keyHash(AccountFixtures.publicKey(fixture.keys.get(id))),
                        0,
                        address,
                        1,
                        28);
                var publicKey = AccountFixtures.publicKey(fixture.keys.get(id));
                if (withKid) {
                    var secret = ((EdECPrivateKey) fixture.keys.get(id).getPrivate()).getBytes().orElseThrow();
                    var response = CIP30DataSigner.INSTANCE.signData(address, message, secret, publicKey);
                    packed = BrowserSignatures.fromCip30(publicKey, message, response.signature(), response.key(), 0);
                } else {
                    var signature = AccountFixtures.sign(id, fixture.keys.get(id),
                            BrowserSignatures.signingStructure(address, message)).signature();
                    packed = Arrays.copyOf(address, address.length + 64);
                    System.arraycopy(signature, 0, packed, address.length, 64);
                }
                assertTrue(
                        BrowserSignatures.verify(
                                AccountFixtures.publicKey(fixture.keys.get(id)),
                                message,
                                packed,
                                0));
            }
            evidence.add(new Signature(BigInteger.valueOf(id), packed));
        }
        return AccountFixtures.list(evidence.toArray(Signature[]::new));
    }
}
