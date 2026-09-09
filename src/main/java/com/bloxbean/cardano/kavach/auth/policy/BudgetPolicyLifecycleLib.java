package com.bloxbean.cardano.kavach.auth.policy;

import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;

import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib;
import com.bloxbean.cardano.julc.ledger.ScriptContext;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Budget-aware mixed-policy module administration and recovery proof rules. Old-role authority cannot be
 * replaced by candidate possession. Recovery completion verifies the stored target's
 * complete destination registry without requiring the lost old spend key.
 */
@OnchainLibrary
public class BudgetPolicyLifecycleLib {
    /**
     * Canonical configuration-possession domain, distinct from operation approval.
     *
     * @param tag          fixed configuration domain tag
     * @param intentDigest exact canonical operation digest
     * @param destination  exact destination module reference
     * @param configDigest canonical destination configuration digest
     */
    public record ConfigProofEnvelope(byte[] tag, byte[] intentDigest, AuthModuleRef destination, byte[] configDigest) {
    }

    /**
     * Canonical target-possession domain defined in module-abi.md.
     *
     * @param tag                fixed recovery target domain tag
     * @param completionDomain   exact consumed pending-state signature domain
     * @param sequence           authoritative pending attempt number
     * @param proposalCommitment stored initiation commitment
     * @param configDigest       exact retained target configuration digest
     */
    public record TargetProofEnvelope(byte[] tag, IntentDomain completionDomain, BigInteger sequence,
                                      byte[] proposalCommitment, byte[] configDigest) {
    }

    /**
     * Verifies the selected browser-scheme old-role approval, including canonical optional/proof shape.
     *
     * @param supplied untrusted optional operation proof
     * @param current  authenticated current configuration
     * @param selected validated old policy for this operation
     * @param request  canonical operation envelope
     * @return whether canonical distinct old-key signatures meet the required old role
     */
    public static boolean approval(Optional<Proof> supplied, PolicyConfig current, ThresholdPolicy selected, IntentEnvelope request, BigInteger network, ScriptContext ctx) {
        if (supplied.isEmpty()) return false;
        var proof = supplied.get();
        return Builtins.equalsData((PlutusData) (Object) supplied,
                (PlutusData) (Object) Optional.of(new Proof(BigInteger.valueOf(4), proof.signatures())))
                && PolicyLib.signatures(proof.signatures(), current, AccountLib.digest(request), 8, network, ctx)
                && Ed25519Lib.threshold(proof.signatures(), selected);
    }

    /**
     * Computes the canonical configuration-possession digest for the exact destination.
     *
     * @param request     full replacement intent
     * @param destination destination module reference
     * @param config      complete new configuration
     * @return separate 32-byte possession digest
     */
    public static byte[] configMessage(IntentEnvelope request, AuthModuleRef destination, PlutusData config) {
        var preimage = new ConfigProofEnvelope(new byte[]{75, 65, 86, 65, 67, 72, 95, 67, 79, 78, 70, 73, 71, 95, 80, 79, 83, 83, 69, 83, 83, 73, 79, 78, 95, 86, 49},
                AccountLib.digest(request), destination, Builtins.blake2b_256(Builtins.serialiseData(config)));
        return Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) preimage));
    }

    /**
     * Requires every destination key, including retained keys, in its configured method.
     * This also proves any valid destination threshold without permitting method-change bypasses.
     *
     * @param evidence    sorted evidence under the destination registry
     * @param destination fully validated destination configuration
     * @param message     configuration or recovery-target digest
     * @return whether every destination credential proves possession
     */
    public static boolean possession(JulcList<Signature> evidence,
                                     PolicyConfig destination, byte[] message, BigInteger network, ScriptContext ctx) {
        return AccountLib.listSize((JulcList<PlutusData>) (Object) evidence)
                == AccountLib.listSize((JulcList<PlutusData>) (Object) destination.roles().keys())
                && PolicyLib.signatures(evidence, destination, message, 16, network, ctx);
    }

    /**
     * Validates a new module's configuration and all registry keys. Performs no old-role approval.
     *
     * @param invocation  candidate invocation with absent operation proof
     * @param replacement exact module replacement action
     * @return whether every candidate key proves possession under the installation domain
     */
    public static boolean candidate(ModuleRedeemer invocation, ReplaceModule replacement, BigInteger network, ScriptContext ctx) {
        if (!Builtins.equalsData((PlutusData) (Object) invocation.operationProof(), (PlutusData) (Object) Optional.empty()))
            return false;
        var config = BudgetPolicyLib.authorization(replacement.newConfig());
        return PolicyLib.configuration(config)
                && possession(invocation.configPossession(), config,
                configMessage(invocation.intent(), replacement.newModule(), replacement.newConfig()), network, ctx);
    }

    /**
     * Applies old-module role and target checks; immutable core separately enforces lifecycle.
     *
     * @param invocation canonical invocation at the currently installed module
     * @param previous   authenticated consumed old state
     * @return whether exactly the required old-role or committed-target authorization holds
     */
    public static boolean current(ModuleRedeemer invocation, AccountState previous, BigInteger network, ScriptContext ctx) {
        var config = BudgetPolicyLib.authorization(previous.authConfig());
        if (!PolicyLib.configuration(config)) return false;
        boolean completing = switch (invocation.intent().action()) {
            case CompleteRecovery finish -> true;
            default -> false;
        };
        if (!completing && !roleApproval(invocation, config, network, ctx)) return false;
        return switch (invocation.intent().action()) {
            case Spend spend -> invocation.configPossession().isEmpty();
            case TransferWholeUtxo whole -> invocation.configPossession().isEmpty();
            case ReplaceConfig replacement -> {
                var target = BudgetPolicyLib.authorization(replacement.newConfig());
                yield PolicyLib.configuration(target)
                        && possession(invocation.configPossession(), target,
                        configMessage(invocation.intent(), previous.authModule(), replacement.newConfig()), network, ctx);
            }
            case ReplaceModule replacement -> invocation.configPossession().isEmpty();
            case Freeze freeze -> invocation.configPossession().isEmpty();
            case Unfreeze unfreeze -> invocation.configPossession().isEmpty();
            case StartRecovery start -> invocation.configPossession().isEmpty()
                    && PolicyLib.configuration(BudgetPolicyLib.authorization(start.replacementConfig()));
            case CancelRecovery cancel -> invocation.configPossession().isEmpty();
            case CompleteRecovery completion -> {
                boolean absent = Builtins.equalsData((PlutusData) (Object) invocation.operationProof(), (PlutusData) (Object) Optional.empty());
                yield absent && switch (previous.mode()) {
                    case RecoveryPending pending -> {
                        var target = BudgetPolicyLib.authorization(completion.replacementConfig());
                        var preimage = new TargetProofEnvelope(new byte[]{75, 65, 86, 65, 67, 72, 95, 82, 69, 67, 79, 86, 69, 82, 89, 95, 84, 65, 82, 71, 69, 84, 95, 86, 49},
                                invocation.intent().domain(), previous.recoverySequence(), pending.proposalCommitment(),
                                Builtins.blake2b_256(Builtins.serialiseData(completion.replacementConfig())));
                        yield completion.currentSequence().equals(previous.recoverySequence())
                                && Builtins.equalsData(completion.replacementConfig(), pending.targetConfig())
                                && PolicyLib.configuration(target)
                                && possession(invocation.configPossession(), target,
                                Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) preimage)), network, ctx);
                    }
                    default -> false;
                };
            }
            default -> false;
        };
    }

    /**
     * Selects one authenticated old role, then verifies its evidence exactly once.
     */
    static boolean roleApproval(ModuleRedeemer invocation, PolicyConfig currentConfig, BigInteger network, ScriptContext ctx) {
        ThresholdPolicy selected = switch (invocation.intent().action()) {
            case Spend spend -> PolicyLib.spendPolicy(currentConfig, invocation.intent().action());
            case TransferWholeUtxo whole -> currentConfig.roles().spend();
            case ReplaceConfig replacement -> currentConfig.roles().admin();
            case ReplaceModule replacement -> currentConfig.roles().admin();
            case Freeze freeze -> currentConfig.roles().freeze();
            case Unfreeze unfreeze -> currentConfig.roles().unfreeze();
            case StartRecovery start -> currentConfig.roles().recovery();
            case CancelRecovery cancel -> currentConfig.roles().cancel();
            default -> (ThresholdPolicy) (Object) Builtins.error();
        };
        return approval(invocation.operationProof(), currentConfig, selected, invocation.intent(), network, ctx);
    }
}
