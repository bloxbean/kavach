package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.*;

import java.math.BigInteger;

/**
 * Optional shared counter custody for the separate budget-capable asset profile.
 * Parameters: coreVersion, deploymentDomain, accountId, stateValidatorHash, coreCheckpointHash.
 * Authenticates the full current account and counter NFTs, binds the ordinary intent to its
 * immutable checkpoint, and atomically enforces cumulative actual ADA debit. Counter deposits
 * remain locked in this development candidate; there is no unrelated withdrawal or reset path.
 */
@SpendingValidator
public class PeriodicBudgetValidator {
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

    /**
     * Enforces an exact counter successor under the same core-authorized account spend.
     */
    @Entrypoint
    public static boolean validate(AssetRedeemer redeemer, ScriptContext ctx) {
        if (!coreVersion.equals(BigInteger.ONE) || !redeemer.abiVersion().equals(BigInteger.ONE)
                || !AccountLib.shape((PlutusData) (Object) redeemer, 0, 2)) return false;
        var purpose = new ScriptPurpose.Rewarding(AccountLib.script(coreCheckpointHash));
        if (!ctx.txInfo().withdrawals().containsKey(AccountLib.script(coreCheckpointHash))
                || !ctx.txInfo().redeemers().containsKey(purpose)) return false;
        var core = (CoreRedeemer) (Object) ctx.txInfo().redeemers().get(purpose);
        if (!core.abiVersion().equals(BigInteger.ONE) || !AccountLib.envelope(core.intent(), ctx)
                || !Builtins.equalsByteString(AccountLib.digest(core.intent()), redeemer.intentDigest())
                || !Builtins.equalsData((PlutusData) (Object) core.intent().domain().accountId(), (PlutusData) (Object) accountId))
            return false;
        boolean transfer = switch (core.intent().action()) {
            case Spend spend -> true;
            case TransferWholeUtxo whole -> true;
            default -> false;
        };
        if (!transfer) return false;
        var state = AccountLib.resolve(core.intent().domain(), ctx);
        if (!AccountLib.authenticate(state, core.intent().domain(), deploymentDomain, stateValidatorHash, ctx)
                || !Builtins.equalsByteString(state.coreBinding().checkpoint(), coreCheckpointHash)
                || !PeriodicBudgetLib.isProfile(state.authConfig())) return false;
        var configuration = (Configuration) (Object) state.authConfig();
        if (!PeriodicBudgetLib.configuration(configuration) || !PeriodicBudgetLib.enabled(state.authConfig()))
            return false;
        var budget = configuration.budget().get();
        var ownRef = switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript own -> own.txOutRef();
            default -> core.intent().domain().stateRef();
        };
        var accountAddress = AccountLib.enterprise(state.coreBinding().assetValidator());
        BigInteger inputAda = BigInteger.ZERO;
        BigInteger changeAda = BigInteger.ZERO;
        int ownMatches = 0;
        boolean valid = true;
        for (var input : ctx.txInfo().inputs()) {
            if (input.resolved().address().equals(accountAddress))
                inputAda = inputAda.add(ValuesLib.lovelaceOf(input.resolved().value()));
            if (input.outRef().equals(ownRef)) {
                ownMatches = ownMatches + 1;
                var previous = switch (input.resolved().datum()) {
                    case OutputDatum.OutputDatumInline inline -> (Usage) (Object) inline.datum();
                    default -> (Usage) (Object) Builtins.error();
                };
                if (AccountLib.assetCount(input.resolved().value()) != 2
                        || !ValuesLib.assetOf(input.resolved().value(), budget.counter().policy(), budget.counter().name()).equals(BigInteger.ONE)
                        || !AccountLib.uint63(ValuesLib.lovelaceOf(input.resolved().value()))
                        || AccountLib.atMost(ValuesLib.lovelaceOf(input.resolved().value()), BigInteger.ZERO)
                        || input.resolved().referenceScript().isPresent() || !PeriodicBudgetLib.usageShape(previous))
                    valid = false;
            }
        }
        for (var output : ctx.txInfo().outputs())
            if (output.address().equals(accountAddress))
                changeAda = changeAda.add(ValuesLib.lovelaceOf(output.value()));
        if (!valid || ownMatches != 1 || !AccountLib.uint63(inputAda) || !AccountLib.uint63(changeAda)) return false;
        int matches = 0;
        for (var input : ctx.txInfo().inputs()) {
            if (input.outRef().equals(ownRef)) {
                var previous = switch (input.resolved().datum()) {
                    case OutputDatum.OutputDatumInline inline -> (Usage) (Object) inline.datum();
                    default -> (Usage) (Object) Builtins.error();
                };
                var next = PeriodicBudgetLib.successor(previous, budget, inputAda.subtract(changeAda), ctx);
                for (var output : ctx.txInfo().outputs()) {
                    if (!ValuesLib.assetOf(output.value(), budget.counter().policy(), budget.counter().name()).equals(BigInteger.ZERO)) {
                        matches = matches + 1;
                        boolean datum = switch (output.datum()) {
                            case OutputDatum.OutputDatumInline inline ->
                                    Builtins.equalsData(inline.datum(), (PlutusData) (Object) next);
                            default -> false;
                        };
                        if (!datum || !output.address().equals(input.resolved().address()) || output.referenceScript().isPresent()
                                || !Builtins.equalsData((PlutusData) (Object) output.value(), (PlutusData) (Object) input.resolved().value()))
                            valid = false;
                    }
                }
            }
        }
        return valid && matches == 1;
    }
}
