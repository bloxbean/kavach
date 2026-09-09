package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.AccountTypes;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

/**
 * Immutable transaction-level transfer authorization. Authenticates canonical state,
 * exact replay resources independently of module approval. The required anchor asset
 * input executes value accounting; checkpoint success alone is insufficient for a spend.
 * State mutations instead require the consumed immutable state validator as their anchor.
 * Parameters follow deployment.md in declaration order.
 * <p>Immutable parameters, applied from left to right: {@code coreVersion, deploymentDomain, stateValidatorHash, rewardSink}.</p>
 * <p>Entrypoints are compiled to UPLC. A false result or script evaluation error
 * rejects the transaction; invoking these Java methods directly is not a ledger test.</p>
 */
@MultiValidator
public class CoreCheckpoint {
    @Param
    static BigInteger coreVersion;
    @Param
    static DeploymentDomain deploymentDomain;
    @Param
    static byte[] stateValidatorHash;
    @Param
    static Address rewardSink;

    /**
     * Allows only the distinct empty registration redeemer under Certifying.
     *
     * @param data untrusted ABI Data
     * @param ctx  ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.CERTIFY)
    public static boolean certify(PlutusData data, ScriptContext ctx) {
        var invocation = (CoreInvocation) (Object) data;
        return coreVersion.equals(BigInteger.ONE) && Builtins.equalsData((PlutusData) (Object) invocation,
                (PlutusData) (Object) new CoreRegistration()) && AccountLib.registration(ctx);
    }

    /**
     * Requires a canonical ordinary invocation under this checkpoint's Rewarding purpose.
     *
     * @param data untrusted ABI Data
     * @param ctx  ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.WITHDRAW)
    public static boolean reward(PlutusData data, ScriptContext ctx) {
        var invocation = (CoreInvocation) (Object) data;
        return switch (invocation) {
            case CoreRedeemer core -> switch (core.intent().action()) {
                case Spend spend -> transfer(core, ctx);
                case TransferWholeUtxo whole -> transfer(core, ctx);
                default -> mutate(core, ctx);
            };
            default -> false;
        };
    }

    /**
     * Composes state, module, receipts and value/replay checks without trusting relayer hints.
     */
    static boolean transfer(CoreRedeemer core, ScriptContext ctx) {
        if (!coreVersion.equals(BigInteger.ONE) || !core.abiVersion().equals(BigInteger.ONE) || !AccountLib.transactionShape(ctx)
                || !(AccountLib.assetCount(ctx.txInfo().mint()) == 0) || ctx.txInfo().withdrawals().size() != 2
                || !Builtins.equalsData((PlutusData) (Object) core, (PlutusData) (Object) new CoreRedeemer(core.abiVersion(), core.intent(), core.receipts()))
                || !AccountLib.envelope(core.intent(), ctx)) return false;
        var state = AccountLib.resolve(core.intent().domain(), ctx);
        if (!AccountLib.authenticate(state, core.intent().domain(), deploymentDomain, stateValidatorHash, ctx))
            return false;
        var own = AccountLib.script(state.coreBinding().checkpoint());
        boolean purpose = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript rewarding -> rewarding.credential().equals(own);
            default -> false;
        };
        var module = AccountLib.script(state.authModule().scriptHash());
        var modulePurpose = new ScriptPurpose.Rewarding(module);
        var ownPurpose = new ScriptPurpose.Rewarding(own);
        if (!purpose || !ctx.txInfo().withdrawals().containsKey(own) || !ctx.txInfo().withdrawals().containsKey(module)
                || !ctx.txInfo().redeemers().containsKey(modulePurpose) || !ctx.txInfo().redeemers().containsKey(ownPurpose)
                || !Builtins.equalsData(ctx.txInfo().redeemers().get(ownPurpose), (PlutusData) (Object) core))
            return false;
        var auth = (ModuleRedeemer) (Object) ctx.txInfo().redeemers().get(modulePurpose);
        var expected = new ModuleRedeemer(BigInteger.ONE, core.intent(), auth.operationProof(), auth.configPossession(), core.receipts());
        if (!Builtins.equalsData((PlutusData) (Object) auth, (PlutusData) (Object) expected) || !auth.configPossession().isEmpty()
                || !AccountLib.receipts(core.receipts(), ctx) || !AccountLib.ownSink(own, rewardSink, core.receipts(), ctx))
            return false;
        var accountAddress = AccountLib.enterprise(state.coreBinding().assetValidator());
        var anchor = switch (core.intent().action()) {
            case Spend spend -> spend.accountInputs().head();
            case TransferWholeUtxo whole -> whole.accountInput();
            default -> core.intent().domain().stateRef();
        };
        boolean anchorPresent = false;
        for (var input : ctx.txInfo().inputs()) {
            if (input.outRef().equals(anchor) && input.resolved().address().equals(accountAddress))
                anchorPresent = true;
        }
        boolean supported = switch (core.intent().action()) {
            case Spend spend -> true;
            case TransferWholeUtxo whole -> true;
            default -> false;
        };
        // This exact consumed anchor must execute the immutable asset validator's accounting branch.
        return anchorPresent && supported;
    }

    /**
     * Requires old-module approval and a consumed immutable state anchor for a mutation.
     */
    static boolean mutate(CoreRedeemer invocation, ScriptContext ctx) {
        if (!coreVersion.equals(BigInteger.ONE) || !AccountLib.envelope(invocation.intent(), ctx)) return false;
        var previous = StateTransitionLib.resolve(invocation.intent().domain(), ctx);
        if (!StateTransitionLib.authenticate(previous, invocation.intent().domain(), deploymentDomain, stateValidatorHash, ctx)
                || !StateTransitionLib.coreBinding(invocation, previous, ctx)) return false;
        var own = AccountLib.script(previous.coreBinding().checkpoint());
        boolean purpose = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> reward.credential().equals(own);
            default -> false;
        };
        var statePurpose = new ScriptPurpose.Spending(invocation.intent().domain().stateRef());
        if (!purpose || !ctx.txInfo().redeemers().containsKey(statePurpose)
                || !Builtins.equalsData(ctx.txInfo().redeemers().get(statePurpose), (PlutusData) (Object) invocation)
                || !AccountLib.receipts(invocation.receipts(), ctx)
                || !AccountLib.ownSink(own, rewardSink, invocation.receipts(), ctx)) return false;
        var oldCredential = AccountLib.script(previous.authModule().scriptHash());
        if (!moduleInvocation(invocation, oldCredential, ctx)) return false;
        return switch (invocation.intent().action()) {
            case ReplaceModule replacement -> {
                var candidate = AccountLib.script(replacement.newModule().scriptHash());
                yield AccountLib.moduleShape(replacement.newModule()) && !candidate.equals(oldCredential) && !candidate.equals(own)
                        && ctx.txInfo().withdrawals().size() == 3 && moduleInvocation(invocation, candidate, ctx);
            }
            case ReplaceConfig change -> ctx.txInfo().withdrawals().size() == 2;
            case Freeze freeze -> ctx.txInfo().withdrawals().size() == 2;
            case Unfreeze unfreeze -> ctx.txInfo().withdrawals().size() == 2;
            case StartRecovery start -> ctx.txInfo().withdrawals().size() == 2;
            case CancelRecovery cancel -> ctx.txInfo().withdrawals().size() == 2;
            case CompleteRecovery complete -> ctx.txInfo().withdrawals().size() == 2;
            default -> false;
        };
    }

    /**
     * Binds each independently executing module to the exact same envelope and receipt table.
     */
    static boolean moduleInvocation(CoreRedeemer invocation, Credential selected, ScriptContext ctx) {
        var purpose = new ScriptPurpose.Rewarding(selected);
        if (!ctx.txInfo().withdrawals().containsKey(selected) || !ctx.txInfo().redeemers().containsKey(purpose))
            return false;
        var module = (ModuleRedeemer) (Object) ctx.txInfo().redeemers().get(purpose);
        return Builtins.equalsData((PlutusData) (Object) module, (PlutusData) (Object) new ModuleRedeemer(BigInteger.ONE,
                invocation.intent(), module.operationProof(), module.configPossession(), invocation.receipts()));
    }
}
