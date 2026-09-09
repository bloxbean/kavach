package com.bloxbean.cardano.kavach.auth.policy;

import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;

import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib;

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
import com.bloxbean.cardano.kavach.contracts.AccountTypes;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.Ed25519Config;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Amount-tiered mixed browser authorization candidate. Each configured credential is fixed
 * to COSE or transaction-witness evidence; proofs cannot choose their own verification mode.
 * Small ADA allocations plus the signed maximum account fee select a bounded low-value policy.
 * All other transfers require the strong spend policy; administration and recovery remain separate.
 * Parameters, in order: moduleVersion, abiVersion, deploymentDomain, stateValidatorHash,
 * coreCheckpointHash, rewardSink. This module does not change immutable core scripts.
 */
@MultiValidator
public class PolicyModule {
    @Param static BigInteger moduleVersion;
    @Param static BigInteger abiVersion;
    @Param static DeploymentDomain deploymentDomain;
    @Param static byte[] stateValidatorHash;
    @Param static byte[] coreCheckpointHash;
    @Param static Address rewardSink;

    /**
     * Certifying cannot satisfy either operation approval or genesis possession.
     * @param data untrusted ABI Data
     * @param ctx ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.CERTIFY)
    public static boolean certify(PlutusData data, ScriptContext ctx) {
        var invocation = (ModuleInvocation)(Object)data;
        return moduleVersion.equals(BigInteger.ONE) && abiVersion.equals(BigInteger.ONE)
                && Builtins.equalsData((PlutusData)(Object)invocation, (PlutusData)(Object)new ModuleRegistration()) && AccountLib.registration(ctx);
    }
    /**
     * Dispatches distinct ordinary/genesis ABI variants, rejecting unknown purposes or variants.
     * @param data untrusted ABI Data
     * @param ctx ledger-supplied context for the selected script purpose
     * @return true for an accepted operation; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.WITHDRAW)
    public static boolean reward(PlutusData data, ScriptContext ctx) {
        var invocation = (ModuleInvocation)(Object)data;
        if (!moduleVersion.equals(BigInteger.ONE) || !abiVersion.equals(BigInteger.ONE)) return false;
        return switch (invocation) {
            case ModuleRedeemer operation -> switch (operation.intent().action()) {
                case Spend spend -> authorize(operation, ctx);
                case TransferWholeUtxo whole -> authorize(operation, ctx);
                default -> mutate(operation, ctx);
            };
            default -> false;
        };
    }
    /**
     * Validates spend-role evidence under the existing authenticated state. The dispatcher
     * selects only transfer actions; {@code current} requires present old-role approval and
     * empty configuration possession before success.
     */
    static boolean authorize(ModuleRedeemer invocation, ScriptContext ctx) {
        if (!AccountLib.transactionShape(ctx) || !invocation.abiVersion().equals(BigInteger.ONE) || !AccountLib.envelope(invocation.intent(), ctx)
                || ctx.txInfo().withdrawals().size() != 2 || !(AccountLib.assetCount(ctx.txInfo().mint()) == 0)) return false;
        var state = AccountLib.resolve(invocation.intent().domain(), ctx);
        if (!AccountLib.authenticate(state, invocation.intent().domain(), deploymentDomain, stateValidatorHash, ctx)
                || !Builtins.equalsByteString(state.coreBinding().checkpoint(), coreCheckpointHash)) return false;
        var own = AccountLib.script(state.authModule().scriptHash());
        if (!bound(invocation, state, own, ctx)) return false;
        return PolicyLifecycleLib.current(invocation, state, deploymentDomain.networkId(), ctx);
    }
    /** Independently authenticates old state; candidate validation cannot substitute for old authority. */
    static boolean mutate(ModuleRedeemer invocation, ScriptContext ctx) {
        if (!invocation.abiVersion().equals(BigInteger.ONE) || !AccountLib.envelope(invocation.intent(), ctx)) return false;
        var previous = StateTransitionLib.resolve(invocation.intent().domain(), ctx);
        if (!StateTransitionLib.authenticate(previous, invocation.intent().domain(), deploymentDomain, stateValidatorHash, ctx)
                || !Builtins.equalsByteString(previous.coreBinding().checkpoint(), coreCheckpointHash)) return false;
        var own = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> reward.credential();
            default -> AccountLib.script(new byte[]{});
        };
        var oldCredential = AccountLib.script(previous.authModule().scriptHash());
        var coreInvocation = new CoreRedeemer(BigInteger.ONE, invocation.intent(), invocation.receipts());
        var statePurpose = new ScriptPurpose.Spending(invocation.intent().domain().stateRef());
        if (!bound(invocation, previous, own, ctx) || !ctx.txInfo().redeemers().containsKey(statePurpose)
                || !Builtins.equalsData(ctx.txInfo().redeemers().get(statePurpose), (PlutusData)(Object)coreInvocation)) return false;
        return switch (invocation.intent().action()) {
            case ReplaceModule replacement -> {
                var candidate = AccountLib.script(replacement.newModule().scriptHash());
                yield AccountLib.moduleShape(replacement.newModule()) && !candidate.equals(oldCredential)
                        && !candidate.equals(AccountLib.script(coreCheckpointHash)) && ctx.txInfo().withdrawals().size() == 3
                        && ctx.txInfo().withdrawals().containsKey(oldCredential) && ctx.txInfo().withdrawals().containsKey(candidate)
                        && (own.equals(oldCredential) ? PolicyLifecycleLib.current(invocation, previous, deploymentDomain.networkId(), ctx)
                            : own.equals(candidate) && PolicyLifecycleLib.candidate(invocation, replacement, deploymentDomain.networkId(), ctx));
            }
            default -> ctx.txInfo().withdrawals().size() == 2 && own.equals(oldCredential)
                    && PolicyLifecycleLib.current(invocation, previous, deploymentDomain.networkId(), ctx);
        };
    }
    /** Shared exact purpose/envelope/receipt binding for both reference-state and consumed-state operations. */
    static boolean bound(ModuleRedeemer invocation, AccountState previous,
            Credential expectedOwn, ScriptContext ctx) {
        boolean correctPurpose = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> reward.credential().equals(expectedOwn);
            default -> false;
        };
        var ownPurpose = new ScriptPurpose.Rewarding(expectedOwn);
        var coreInvocation = new CoreRedeemer(BigInteger.ONE, invocation.intent(), invocation.receipts());
        return correctPurpose && StateTransitionLib.coreBinding(coreInvocation, previous, ctx)
                && AccountLib.shape((PlutusData)(Object)invocation, 0, 5)
                && ctx.txInfo().withdrawals().containsKey(expectedOwn) && ctx.txInfo().redeemers().containsKey(ownPurpose)
                && Builtins.equalsData(ctx.txInfo().redeemers().get(ownPurpose), (PlutusData)(Object)invocation)
                && AccountLib.receipts(invocation.receipts(), ctx)
                && AccountLib.ownSink(expectedOwn, rewardSink, invocation.receipts(), ctx);
    }
}
