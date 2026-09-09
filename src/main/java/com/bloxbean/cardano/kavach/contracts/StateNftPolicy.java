package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

import java.math.BigInteger;

/**
 * Creator-bound one-shot state NFT policy. The seed and required creator transaction
 * signature authenticate the final state without placing its own policy ID in parameters.
 * Initialization requires the selected module's exact genesis Rewarding invocation.
 * Burning and subsequent minting are unsupported. Mint redeemer is integer zero.
 * <p>Immutable parameters, applied from left to right: {@code coreVersion, deploymentDomain, seed, creator, stateValidatorHash}.</p>
 * <p>Entrypoints are compiled to UPLC. A false result or script evaluation error
 * rejects the transaction; invoking these Java methods directly is not a ledger test.</p>
 */
@MintingValidator
public class StateNftPolicy {
    @Param
    static BigInteger coreVersion;
    @Param
    static DeploymentDomain deploymentDomain;
    @Param
    static TxOutRef seed;
    @Param
    static byte[] creator;
    @Param
    static byte[] stateValidatorHash;

    /**
     * Initializes exactly one correctly formed NFT state output with creator and module approval.
     *
     * @param redeemer untrusted redeemer
     * @param ctx      ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        if (!redeemer.equals(BigInteger.ZERO) || !coreVersion.equals(BigInteger.ONE) || !AccountLib.transactionShape(ctx))
            return false;
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript minting -> initialize(ctx, minting);
            default -> false;
        };
    }

    /**
     * Checks the one-shot seed, exact supply and canonical module-bound genesis state.
     */
    static boolean initialize(ScriptContext ctx, ScriptInfo.MintingScript minting) {
        var policyId = minting.policyId();
        // Minting-purpose newtypes are already unwrapped by this pinned compiler.
        byte[] ownPolicy = Builtins.toByteString(policyId);
        boolean signed = false;
        boolean consumed = false;
        boolean inputsValid = true;
        for (var signer : ctx.txInfo().signatories())
            if (Builtins.equalsByteString(signer.hash(), creator)) signed = true;
        for (var input : ctx.txInfo().inputs()) {
            boolean key = switch (input.resolved().address().credential()) {
                case Credential.PubKeyCredential credential -> true;
                default -> false;
            };
            if (!key) inputsValid = false;
            if (input.outRef().equals(seed)) consumed = switch (input.resolved().address().credential()) {
                case Credential.PubKeyCredential credential ->
                        Builtins.equalsByteString(credential.hash().hash(), creator);
                default -> false;
            };
        }
        if (!signed || !consumed || !inputsValid || AccountLib.assetCount(ctx.txInfo().mint()) != 1
                || !ValuesLib.assetOf(ctx.txInfo().mint(), ownPolicy, new byte[]{}).equals(BigInteger.ONE)
                || ctx.txInfo().withdrawals().size() != 1) return false;
        int matches = 0;
        int position = 0;
        int stateIndex = -1;
        for (var output : ctx.txInfo().outputs()) {
            if (!AccountLib.equalInteger(ValuesLib.assetOf(output.value(), ownPolicy, new byte[]{}), BigInteger.ZERO)) {
                matches = matches + 1;
                stateIndex = position;
            }
            position = position + 1;
        }
        if (matches != 1) return false;
        var output = ctx.txInfo().outputs().get(stateIndex);
        AccountState state = switch (output.datum()) {
            case OutputDatum.OutputDatumInline inline -> (AccountState) (Object) inline.datum();
            default -> (AccountState) (Object) Builtins.error();
        };
        if (!AccountLib.normalState(state, deploymentDomain, stateValidatorHash)
                || !Builtins.equalsByteString(state.accountId().policy(), ownPolicy)
                || !state.stateVersion().equals(BigInteger.ZERO) || !state.recoverySequence().equals(BigInteger.ZERO)
                || !state.recoveryNotBefore().equals(BigInteger.ZERO) || !AccountLib.stateOutput(output, state, stateValidatorHash))
            return false;
        var module = AccountLib.script(state.authModule().scriptHash());
        var purpose = new ScriptPurpose.Rewarding(module);
        if (!ctx.txInfo().withdrawals().containsKey(module) || !ctx.txInfo().redeemers().containsKey(purpose))
            return false;
        var genesis = (GenesisModuleRedeemer) (Object) ctx.txInfo().redeemers().get(purpose);
        return Builtins.equalsData((PlutusData) (Object) genesis, (PlutusData) (Object) new GenesisModuleRedeemer(BigInteger.ONE, state, genesis.configPossession(), genesis.receipts()))
                && AccountLib.receipts(genesis.receipts(), ctx) && !AccountLib.receiptAt(genesis.receipts(), BigInteger.valueOf(stateIndex));
    }
}
