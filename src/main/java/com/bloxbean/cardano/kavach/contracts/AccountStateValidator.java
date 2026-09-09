package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.AccountTypes;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.CoreRedeemer;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.DeploymentDomain;

import java.math.BigInteger;

/**
 * Immutable state custody and lifecycle enforcement. Consumes the authenticated current
 * NFT and requires exactly one fully determined successor without reducing its ADA.
 * The old core checkpoint must approve the same intent and invoke the configured module.
 * NFT-less deposits and unsupported actions have no spending path.
 * <p>Immutable parameters, in order: {@code coreVersion, deploymentDomain}.</p>
 * <p>Phase 2 implementation under qualification; this changes the sealed Phase 1 hash.</p>
 */
@SpendingValidator
public class AccountStateValidator {
    @Param
    static BigInteger coreVersion;
    @Param
    static DeploymentDomain deploymentDomain;

    /**
     * Checks the complete mutation at the consumed state input's immutable boundary.
     *
     * @param redeemer exact core/state invocation, never an independent caller-selected digest
     * @param ctx      ledger context selecting this state input under Spending
     * @return true only when custody, lifecycle, successor and core binding all hold
     */
    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        if (!coreVersion.equals(BigInteger.ONE)) return false;
        var invocation = (CoreRedeemer) (Object) redeemer;
        var previous = StateTransitionLib.resolve(invocation.intent().domain(), ctx);
        boolean ownInput = switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript spending ->
                    spending.txOutRef().equals(invocation.intent().domain().stateRef());
            default -> false;
        };
        if (!ownInput || !StateTransitionLib.authenticate(previous, invocation.intent().domain(), deploymentDomain,
                previous.coreBinding().stateValidator(), ctx)
                || !StateTransitionLib.coreBinding(invocation, previous, ctx)
                || !StateTransitionLib.sponsorInputs(invocation, ctx)) return false;
        var nextState = LifecycleLib.successor(previous, invocation.intent(), ctx);
        return StateTransitionLib.outputs(invocation, previous, nextState, ctx);
    }
}
