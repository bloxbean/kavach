package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.NativeAccountingLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

import java.math.BigInteger;

import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Configuration;

/**
 * New-core per-account asset lock with optional cumulative ADA accounting. Every consumed asset input requires the immutable core
 * Rewarding invocation bound to this account/domain and the same canonical digest.
 * The canonical first signed input executes all-asset accounting once. The checkpoint
 * requires that exact anchor at this script, so other inputs cannot skip the check.
 * <p>Immutable parameters, applied from left to right: {@code coreVersion, deploymentDomain, accountId, stateValidatorHash, coreCheckpointHash, budgetValidatorHash}.</p>
 * <p>Entrypoints are compiled to UPLC. A false result or script evaluation error
 * rejects the transaction; invoking these Java methods directly is not a ledger test.</p>
 */
@SpendingValidator
public class BudgetAccountAssetValidator {
    @Param
    static BigInteger coreVersion;
    @Param
    static DeploymentDomain deploymentDomain;
    @Param
    static AccountId accountId;
    @Param
    static byte[] stateValidatorHash;
    @Param
    static byte[] coreCheckpointHash;
    @Param
    static byte[] budgetValidatorHash;

    /**
     * Validates the canonical ABI redeemer and exact immutable checkpoint invocation.
     *
     * @param redeemer untrusted redeemer
     * @param ctx      ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(AssetRedeemer redeemer, ScriptContext ctx) {
        boolean spending = switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript own -> true;
            default -> false;
        };
        var purpose = new ScriptPurpose.Rewarding(AccountLib.script(coreCheckpointHash));
        if (!spending || !coreVersion.equals(BigInteger.ONE) || !redeemer.abiVersion().equals(BigInteger.ONE)
                || !AccountLib.shape((PlutusData) (Object) redeemer, 0, 2)
                || !ctx.txInfo().withdrawals().containsKey(AccountLib.script(coreCheckpointHash)) || !ctx.txInfo().redeemers().containsKey(purpose))
            return false;
        var core = (CoreRedeemer) (Object) ctx.txInfo().redeemers().get(purpose);
        var domain = core.intent().domain();
        boolean binding = core.abiVersion().equals(BigInteger.ONE)
                && Builtins.equalsData((PlutusData) (Object) domain.accountId(), (PlutusData) (Object) accountId)
                && Builtins.equalsData((PlutusData) (Object) domain.deploymentDomain(), (PlutusData) (Object) deploymentDomain)
                && Builtins.equalsByteString(domain.coreBinding().stateValidator(), stateValidatorHash)
                && Builtins.equalsByteString(domain.coreBinding().checkpoint(), coreCheckpointHash)
                && Builtins.equalsByteString(AccountLib.digest(core.intent()), redeemer.intentDigest());
        if (!binding) return false;
        var ownRef = switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript own -> own.txOutRef();
            default -> domain.stateRef();
        };
        // The immutable checkpoint requires the signed first account input at this asset script.
        // That anchor authenticates state and the counter and accounts for every account input.
        // Other inputs retain their own exact account/domain/checkpoint/digest binding above.
        boolean nonAnchor = switch (core.intent().action()) {
            case Spend spend -> !ownRef.equals(spend.accountInputs().head());
            default -> false;
        };
        if (nonAnchor) return true;
        var accountAddress = AccountLib.enterprise(domain.coreBinding().assetValidator());
        var state = AccountLib.resolve(domain, ctx);
        if (!AccountLib.authenticate(state, domain, deploymentDomain, stateValidatorHash, ctx)) return false;
        boolean budgeted = PeriodicBudgetLib.enabled(state.authConfig());
        var budgetAddress = AccountLib.enterprise(budgetValidatorHash);
        if (PeriodicBudgetLib.isProfile(state.authConfig())) {
            var configuration = (Configuration) (Object) state.authConfig();
            if (!PeriodicBudgetLib.configuration(configuration)) return false;
            if (budgeted) {
                var budget = configuration.budget().get();
                int matches = 0;
                boolean valid = true;
                for (var input : ctx.txInfo().inputs()) {
                    if (!ValuesLib.assetOf(input.resolved().value(), budget.counter().policy(), budget.counter().name()).equals(BigInteger.ZERO)) {
                        matches = matches + 1;
                        if (!input.resolved().address().equals(budgetAddress)
                                || !ValuesLib.assetOf(input.resolved().value(), budget.counter().policy(), budget.counter().name()).equals(BigInteger.ONE))
                            valid = false;
                    }
                }
                if (!valid || matches != 1) return false;
            }
        }
        return switch (core.intent().action()) {
            case Spend spend ->
                    !ownRef.equals(spend.accountInputs().head()) || BudgetSpendLib.validate(spend, accountAddress, budgetAddress, budgeted, core.receipts(), ctx);
            case TransferWholeUtxo whole ->
                    ownRef.equals(whole.accountInput()) && BudgetWholeTransferLib.validate(whole, accountAddress, budgetAddress, budgeted, core.receipts(), ctx);
            default -> false;
        };
    }
}
