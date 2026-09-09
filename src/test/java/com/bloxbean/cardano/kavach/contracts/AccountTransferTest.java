package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.vm.EvalResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests compiled artifacts as emitted, without rewriting UPLC.
 */
class AccountTransferTest {
    @Test
    void completeTransferExecutesEveryRequiredScript() throws Exception {
        var fixture = new AccountFixtures();
        var intent = fixture.spend(0);
        var auth = fixture.authorization(intent);
        for (var role : new String[]{"asset", "core", "module"}) {
            var result = fixture.evaluate(role, fixture.context(role, intent, auth));
            var success = assertInstanceOf(EvalResult.Success.class, result, role + ": " + result);
            System.out.println("Phase 1 " + role + " budget " + success.consumed());
        }
    }
}
