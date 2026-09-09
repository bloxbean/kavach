package com.bloxbean.cardano.kavach.auth.policy;

import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;


import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.StateTransitionLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

import java.math.BigInteger;

/**
 * Restricted mixed-signature account setup checkpoint. All keys prove genesis possession in
 * their configured methods. After genesis, only old-admin-authorized installation of the
 * immutable finalModuleHash with identical configuration is permitted; spending is blocked.
 * This keeps setup within the ledger publication size bound without weakening genesis checks.
 * Parameters: moduleVersion, abiVersion, deploymentDomain, stateValidatorHash,
 * coreCheckpointHash, rewardSink, finalModuleHash. Full lifecycle authorization resides in
 * the separately published PolicyModule; its candidate possession is also mandatory.
 */
@MultiValidator
public class MixedSetupModule {
    @Param
    static BigInteger moduleVersion;
    @Param
    static BigInteger abiVersion;
    @Param
    static DeploymentDomain deploymentDomain;
    @Param
    static byte[] stateValidatorHash;
    @Param
    static byte[] coreCheckpointHash;
    @Param
    static Address rewardSink;
    @Param
    static byte[] finalModuleHash;

    /**
     * Certifying cannot satisfy either operation approval or genesis possession.
     *
     * @param data untrusted ABI Data
     * @param ctx  ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.CERTIFY)
    public static boolean certify(PlutusData data, ScriptContext ctx) {
        var invocation = (ModuleInvocation) (Object) data;
        return moduleVersion.equals(BigInteger.ONE) && abiVersion.equals(BigInteger.ONE)
                && Builtins.equalsData((PlutusData) (Object) invocation, (PlutusData) (Object) new ModuleRegistration()) && AccountLib.registration(ctx);
    }

    /**
     * Dispatches distinct ordinary/genesis ABI variants, rejecting unknown purposes or variants.
     *
     * @param data untrusted ABI Data
     * @param ctx  ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.WITHDRAW)
    public static boolean reward(PlutusData data, ScriptContext ctx) {
        var invocation = (ModuleInvocation) (Object) data;
        if (!moduleVersion.equals(BigInteger.ONE) || !abiVersion.equals(BigInteger.ONE)) return false;
        return switch (invocation) {
            case GenesisModuleRedeemer genesis -> initialize(genesis, ctx);
            case ModuleRedeemer operation -> finish(operation, ctx);
            default -> false;
        };
    }

    /**
     * Establishes usable configuration keys before the state NFT is created; no operation proof substitutes.
     */
    static boolean initialize(GenesisModuleRedeemer genesis, ScriptContext ctx) {
        var state = genesis.state();
        if (!AccountLib.transactionShape(ctx) || !genesis.abiVersion().equals(BigInteger.ONE) || !AccountLib.normalState(state, deploymentDomain, stateValidatorHash)
                || !state.stateVersion().equals(BigInteger.ZERO) || !state.recoverySequence().equals(BigInteger.ZERO)
                || !state.recoveryNotBefore().equals(BigInteger.ZERO) || !Builtins.equalsByteString(state.coreBinding().checkpoint(), coreCheckpointHash)
                || ctx.txInfo().withdrawals().size() != 1
                || !AccountLib.shape((PlutusData) (Object) genesis, 2, 4)) return false;
        var own = AccountLib.script(state.authModule().scriptHash());
        boolean purpose = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> reward.credential().equals(own);
            default -> false;
        };
        if (!purpose || !AccountLib.receipts(genesis.receipts(), ctx) || !AccountLib.ownSink(own, rewardSink, genesis.receipts(), ctx)
                || AccountLib.assetCount(ctx.txInfo().mint()) != 1
                || !ValuesLib.assetOf(ctx.txInfo().mint(), state.accountId().policy(), new byte[]{}).equals(BigInteger.ONE))
            return false;
        int matches = 0;
        boolean valid = true;
        int position = 0;
        for (var output : ctx.txInfo().outputs()) {
            if (!AccountLib.equalInteger(ValuesLib.assetOf(output.value(), state.accountId().policy(), new byte[]{}), BigInteger.ZERO)) {
                matches = matches + 1;
                if (!AccountLib.stateOutput(output, state, stateValidatorHash) || AccountLib.receiptAt(genesis.receipts(), BigInteger.valueOf(position)))
                    valid = false;
            }
            position = position + 1;
        }
        var config = (PolicyConfig) (Object) state.authConfig();
        if (!valid || matches != 1 || !PolicyLib.configuration(config) || AccountLib.listSize((JulcList<PlutusData>) (Object) genesis.configPossession()) != AccountLib.listSize((JulcList<PlutusData>) (Object) config.roles().keys()))
            return false;
        var envelope = new GenesisProofEnvelope(new byte[]{75, 65, 86, 65, 67, 72, 95, 71, 69, 78, 69, 83, 73, 83, 95, 80, 79, 83, 83, 69, 83, 83, 73, 79, 78, 95, 86, 49},
                deploymentDomain, state.accountId(), state.coreBinding(), state.authModule(), Builtins.blake2b_256(Builtins.serialiseData(state.authConfig())));
        return PolicyLib.signatures(genesis.configPossession(), config, Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) envelope)), 16, deploymentDomain.networkId(), ctx);
    }

    /**
     * Only the precommitted final module and unchanged configuration can complete setup.
     */
    static boolean finish(ModuleRedeemer invocation, ScriptContext ctx) {
        if (!invocation.abiVersion().equals(BigInteger.ONE) || !AccountLib.envelope(invocation.intent(), ctx))
            return false;
        var previous = StateTransitionLib.resolve(invocation.intent().domain(), ctx);
        if (!StateTransitionLib.authenticate(previous, invocation.intent().domain(), deploymentDomain, stateValidatorHash, ctx)
                || !Builtins.equalsByteString(previous.coreBinding().checkpoint(), coreCheckpointHash)) return false;
        var own = AccountLib.script(previous.authModule().scriptHash());
        var coreInvocation = new CoreRedeemer(BigInteger.ONE, invocation.intent(), invocation.receipts());
        var statePurpose = new ScriptPurpose.Spending(invocation.intent().domain().stateRef());
        var config = (PolicyConfig) (Object) previous.authConfig();
        if (!bound(invocation, previous, own, ctx) || !ctx.txInfo().redeemers().containsKey(statePurpose)
                || !Builtins.equalsData(ctx.txInfo().redeemers().get(statePurpose), (PlutusData) (Object) coreInvocation)
                || !PolicyLib.configuration(config) || !invocation.configPossession().isEmpty()
                || !PolicyLifecycleLib.approval(invocation.operationProof(), config, config.roles().admin(),
                invocation.intent(), deploymentDomain.networkId(), ctx)) return false;
        return switch (invocation.intent().action()) {
            case ReplaceModule replacement -> {
                var candidate = AccountLib.script(replacement.newModule().scriptHash());
                yield AccountLib.moduleShape(replacement.newModule()) && !candidate.equals(own)
                        && !candidate.equals(AccountLib.script(coreCheckpointHash))
                        && Builtins.equalsByteString(replacement.newModule().scriptHash(), finalModuleHash)
                        && Builtins.equalsData(replacement.newConfig(), previous.authConfig())
                        && ctx.txInfo().withdrawals().size() == 3 && ctx.txInfo().withdrawals().containsKey(candidate);
            }
            default -> false;
        };
    }

    /**
     * Exact purpose, core and receipt binding for activation of the consumed account state.
     */
    static boolean bound(ModuleRedeemer invocation, AccountState previous,
                         Credential expectedOwn, ScriptContext ctx) {
        boolean correctPurpose = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> reward.credential().equals(expectedOwn);
            default -> false;
        };
        var ownPurpose = new ScriptPurpose.Rewarding(expectedOwn);
        var coreInvocation = new CoreRedeemer(BigInteger.ONE, invocation.intent(), invocation.receipts());
        return correctPurpose && StateTransitionLib.coreBinding(coreInvocation, previous, ctx)
                && AccountLib.shape((PlutusData) (Object) invocation, 0, 5)
                && ctx.txInfo().withdrawals().containsKey(expectedOwn) && ctx.txInfo().redeemers().containsKey(ownPurpose)
                && Builtins.equalsData(ctx.txInfo().redeemers().get(ownPurpose), (PlutusData) (Object) invocation)
                && AccountLib.receipts(invocation.receipts(), ctx)
                && AccountLib.ownSink(expectedOwn, rewardSink, invocation.receipts(), ctx);
    }
}
