package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.NativeAccountingLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

import java.math.BigInteger;

import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

/**
 * Per-account asset lock. Every consumed asset input requires the immutable core
 * Rewarding invocation bound to this account/domain and the same canonical digest.
 * The canonical first signed input executes all-asset accounting once. The checkpoint
 * requires that exact anchor at this script, so other inputs cannot skip the check.
 * <p>Immutable parameters, applied from left to right: {@code coreVersion, deploymentDomain, accountId, stateValidatorHash, coreCheckpointHash}.</p>
 * <p>Entrypoints are compiled to UPLC. A false result or script evaluation error
 * rejects the transaction; invoking these Java methods directly is not a ledger test.</p>
 */
@SpendingValidator
public class AccountAssetValidator {
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
        var accountAddress = AccountLib.enterprise(domain.coreBinding().assetValidator());
        return switch (core.intent().action()) {
            case Spend spend ->
                    !ownRef.equals(spend.accountInputs().head()) || SpendLib.validate(spend, accountAddress, core.receipts(), ctx);
            case TransferWholeUtxo whole ->
                    ownRef.equals(whole.accountInput()) && WholeTransferLib.validate(whole, accountAddress, core.receipts(), ctx);
            default -> false;
        };
    }
}
