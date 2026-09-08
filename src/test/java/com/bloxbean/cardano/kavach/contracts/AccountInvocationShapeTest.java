package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.CoreRedeemer;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.Freeze;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.Frozen;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Mutates every copy of an invocation consistently, so cross-script equality alone cannot
 * reject it. Each owning validator must still reject noncanonical outer shape and ABI.
 */
class AccountInvocationShapeTest {
    @ParameterizedTest
    @ValueSource(strings = {"state", "core", "module", "spend", "genesis"})
    void canonicalShapeIsRequiredEvenWhenAllCopiesMatch(String target) throws Exception {
        var lifecycle = new AccountLifecycleTest();
        var f = lifecycle.f;
        String role = target.equals("spend") || target.equals("genesis") ? "module" : target;
        PlutusData invocation;
        PlutusData context;
        if (target.equals("genesis")) {
            var creation = new AccountCreationTest();
            var genesis = creation.genesis();
            invocation = AccountCodec.data(genesis);
            context = creation.context("module", genesis).buildPlutusData();
            assertInstanceOf(EvalResult.Success.class, creation.evaluateModule(context), target + " control");
            for (var attack : List.of("extra", "missing", "tag", "abi")) {
                var changed = AccountAdversarialTest.replace(context, invocation, malformed(invocation, attack));
                assertInstanceOf(EvalResult.Failure.class, creation.evaluateModule(changed), target + " " + attack);
            }
            return;
        } else if (target.equals("spend")) {
            var request = f.spend(0);
            var approval = f.authorization(request);
            invocation = AccountCodec.data(approval);
            context = f.context("module", request, approval).buildPlutusData();
        } else {
            var request = lifecycle.intent(f.state, new Freeze(), 1000, 9999);
            var approval = lifecycle.approval(f.state, request, 1);
            var successor = lifecycle.state(f.state, 1, 0, 0, new Frozen());
            invocation = AccountCodec.data(role.equals("module") ? approval
                    : new CoreRedeemer(BigInteger.ONE, request, approval.receipts()));
            context = lifecycle.context(role, f.state, successor, approval, 1000, 9999).buildPlutusData();
        }
        assertInstanceOf(EvalResult.Success.class, f.evaluate(role, context), target + " control");
        for (var attack : List.of("extra", "missing", "tag", "abi")) {
            var changed = AccountAdversarialTest.replace(context, invocation, malformed(invocation, attack));
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, changed), target + " " + attack);
        }
    }

    private static PlutusData malformed(PlutusData original, String attack) {
        var record = (PlutusData.ConstrData) original;
        var fields = new ArrayList<>(record.fields());
        int tag = record.tag();
        switch (attack) {
            case "extra" -> fields.add(PlutusData.integer(0));
            case "missing" -> fields.removeLast();
            case "tag" -> tag = 99;
            case "abi" -> fields.set(0, PlutusData.integer(2));
            default -> throw new AssertionError(attack);
        }
        return new PlutusData.ConstrData(tag, fields);
    }
}
