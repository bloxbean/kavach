package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete mode/action matrix at the immutable state boundary. Core and module authority
 * are tested separately in the composed lifecycle/replacement tests; these contexts isolate
 * mode enforcement even when a matching mandatory core invocation has been supplied.
 */
class AccountLifecycleMatrixTest {
    private final AccountLifecycleTest lifecycle = new AccountLifecycleTest();
    private final AccountFixtures f = lifecycle.f;

    AccountLifecycleMatrixTest() throws Exception {
    }

    @ParameterizedTest
    @CsvSource({
            "normal,config,true", "normal,module,true", "normal,freeze,true", "normal,unfreeze,false",
            "normal,start,true", "normal,cancel,false", "normal,complete,false",
            "frozen,config,false", "frozen,module,false", "frozen,freeze,false", "frozen,unfreeze,true",
            "frozen,start,true", "frozen,cancel,false", "frozen,complete,false",
            "pending,config,false", "pending,module,false", "pending,freeze,false", "pending,unfreeze,false",
            "pending,start,false", "pending,cancel,true", "pending,complete,true"
    })
    void immutableStateAndSdkAgreeOnEveryMutationMode(String mode, String operation, boolean allowed) throws Exception {
        AccountMode oldMode = switch (mode) {
            case "normal" -> new Normal();
            case "frozen" -> new Frozen();
            case "pending" -> new RecoveryPending(new byte[32], BigInteger.valueOf(86400000), f.state.authConfig());
            default -> throw new AssertionError(mode);
        };
        var old = lifecycle.state(f.state, 1, 1, 0, oldMode);
        var candidateHash = new byte[28];
        candidateHash[0] = 42;
        Action action = switch (operation) {
            case "config" -> new ReplaceConfig(old.authConfig());
            case "module" -> new ReplaceModule(new AuthModuleRef(candidateHash, BigInteger.ONE), old.authConfig());
            case "freeze" -> new Freeze();
            case "unfreeze" -> new Unfreeze();
            case "start" -> new StartRecovery(BigInteger.TWO, old.authConfig());
            case "cancel" -> new CancelRecovery(BigInteger.ONE, new byte[32]);
            case "complete" -> new CompleteRecovery(BigInteger.ONE, old.authConfig());
            default -> throw new AssertionError(operation);
        };
        long lower = 86400000, upper = 86409999;
        var request = lifecycle.intent(old, action, lower, upper);
        AccountState next;
        if (allowed)
            next = AccountAdministration.successor(old, request, BigInteger.valueOf(lower), BigInteger.valueOf(upper), true);
        else {
            assertThrows(IllegalArgumentException.class, () -> AccountAdministration.successor(old, request,
                    BigInteger.valueOf(lower), BigInteger.valueOf(upper), true));
            next = lifecycle.state(old, 2, 1, 0, new Normal());
        }
        var authorization = lifecycle.approval(old, request, 0, 1);
        var evaluated = f.evaluate("state", lifecycle.context("state", old, next, authorization, lower, upper));
        if (allowed) assertInstanceOf(EvalResult.Success.class, evaluated, mode + " " + operation + ": " + evaluated);
        else assertInstanceOf(EvalResult.Failure.class, evaluated, mode + " " + operation);
    }
}
