package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

import java.math.BigInteger;

/**
 * Consumed-state authentication and state-deposit conservation. No account assets may
 * be spent in a mutation transaction. The immutable state validator enforces successors;
 * core and modules independently authenticate the same old state and intent.
 */
@OnchainLibrary
public class StateTransitionLib {
    /**
     * Resolves only the consumed reference named by the signed domain.
     *
     * @param binding untrusted signed domain
     * @param ctx     ledger context
     * @return untrusted inline state; callers must authenticate it
     */
    public static AccountState resolve(IntentDomain binding, ScriptContext ctx) {
        return AccountLib.resolveFrom(binding, ctx.txInfo().inputs());
    }

    /**
     * Authenticates singleton NFT custody and exact domain against consumed old state.
     *
     * @param previous    candidate old datum
     * @param binding     signed domain
     * @param deployment  immutable deployment
     * @param custodyHash immutable state hash
     * @param ctx         ledger context
     * @return whether the old consumed state is authentic; the state validator separately restricts sponsor inputs
     */
    public static boolean authenticate(AccountState previous, IntentDomain binding, DeploymentDomain deployment,
                                       byte[] custodyHash, ScriptContext ctx) {
        if (!LifecycleLib.stateShape(previous, deployment, custodyHash)
                || !AccountLib.transactionShape(ctx) || AccountLib.assetCount(ctx.txInfo().mint()) != 0) return false;
        if (!AccountLib.authenticateFrom(previous, binding, deployment, custodyHash, ctx.txInfo().inputs()))
            return false;
        boolean valid = true;
        for (var reference : ctx.txInfo().referenceInputs()) {
            if (reference.outRef().equals(binding.stateRef())
                    || ValuesLib.containsPolicy(reference.resolved().value(), previous.accountId().policy()))
                valid = false;
        }
        return valid;
    }

    /**
     * Prevents state mutations from also consuming account assets or unrelated script inputs.
     *
     * @param invocation exact state invocation
     * @param ctx        ledger context
     * @return whether every non-state input is a plain ADA-only key input
     */
    public static boolean sponsorInputs(CoreRedeemer invocation, ScriptContext ctx) {
        boolean valid = true;
        for (var input : ctx.txInfo().inputs()) {
            if (!input.outRef().equals(invocation.intent().domain().stateRef())) {
                boolean key = switch (input.resolved().address().credential()) {
                    case Credential.PubKeyCredential pubkey -> true;
                    default -> false;
                };
                if (!key || !AccountLib.plain(input.resolved()) || !AccountLib.positiveAdaOnly(input.resolved().value()))
                    valid = false;
            }
        }
        return valid;
    }

    /**
     * Requires exact core invocation under the old immutable checkpoint credential.
     *
     * @param invocation canonical mutation invocation
     * @param previous   authenticated old state
     * @param ctx        ledger context
     * @return whether the Rewarding redeemer and withdrawal match the state invocation
     */
    public static boolean coreBinding(CoreRedeemer invocation, AccountState previous, ScriptContext ctx) {
        var credential = AccountLib.script(previous.coreBinding().checkpoint());
        var purpose = new ScriptPurpose.Rewarding(credential);
        return invocation.abiVersion().equals(BigInteger.ONE)
                && AccountLib.shape((PlutusData) (Object) invocation, 0, 3)
                && ctx.txInfo().withdrawals().containsKey(credential) && ctx.txInfo().redeemers().containsKey(purpose)
                && Builtins.equalsData(ctx.txInfo().redeemers().get(purpose), (PlutusData) (Object) invocation);
    }

    /**
     * Requires exactly one NFT successor with nondecreasing state ADA. All other outputs
     * are plain ADA-only key outputs, so account change and hidden script effects reject.
     *
     * @param invocation canonical state/core invocation
     * @param previous   authenticated consumed state
     * @param nextState  exact successor computed by immutable lifecycle rules
     * @param ctx        ledger context
     * @return whether all successor, value, receipt-disjointness and output constraints hold
     */
    public static boolean outputs(CoreRedeemer invocation, AccountState previous, AccountState nextState, ScriptContext ctx) {
        if (!LifecycleLib.stateShape(nextState, previous.deploymentDomain(), previous.coreBinding().stateValidator()))
            return false;
        BigInteger oldAda = BigInteger.ZERO;
        for (var input : ctx.txInfo().inputs()) {
            if (input.outRef().equals(invocation.intent().domain().stateRef()))
                oldAda = ValuesLib.lovelaceOf(input.resolved().value());
        }
        int matches = 0;
        int index = 0;
        boolean valid = true;
        for (var output : ctx.txInfo().outputs()) {
            if (ValuesLib.containsPolicy(output.value(), previous.accountId().policy())) {
                matches = matches + 1;
                if (!AccountLib.stateOutput(output, nextState, previous.coreBinding().stateValidator())
                        || AccountLib.lessThan(ValuesLib.lovelaceOf(output.value()), oldAda)
                        || AccountLib.receiptAt(invocation.receipts(), BigInteger.valueOf(index))) valid = false;
            } else {
                boolean key = switch (output.address().credential()) {
                    case Credential.PubKeyCredential pubkey -> true;
                    default -> false;
                };
                if (!key || !AccountLib.plain(output) || !AccountLib.positiveAdaOnly(output.value())) valid = false;
            }
            index = index + 1;
        }
        return valid && matches == 1;
    }
}
