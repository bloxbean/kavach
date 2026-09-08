package com.bloxbean.cardano.kavach.auth.ed25519;

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
 * First-module administration and recovery proof rules. Old-role authority cannot be
 * replaced by candidate possession. Recovery completion verifies the stored target's
 * spend threshold and every newly introduced key without requiring the lost spend key.
 */
@OnchainLibrary
public class Ed25519LifecycleLib {
    /**
     * Canonical configuration-possession domain, distinct from operation approval.
     * @param tag fixed configuration domain tag
     * @param intentDigest exact canonical operation digest
     * @param destination exact destination module reference
     * @param configDigest canonical destination configuration digest
     */
    public record ConfigProofEnvelope(byte[] tag, byte[] intentDigest, AuthModuleRef destination, byte[] configDigest) {}
    /**
     * Canonical target-possession domain defined in module-abi.md.
     * @param tag fixed recovery target domain tag
     * @param completionDomain exact consumed pending-state signature domain
     * @param sequence authoritative pending attempt number
     * @param proposalCommitment stored initiation commitment
     * @param configDigest exact retained target configuration digest
     */
    public record TargetProofEnvelope(byte[] tag, IntentDomain completionDomain, BigInteger sequence,
            byte[] proposalCommitment, byte[] configDigest) {}

    /**
     * Verifies exact scheme-zero old-role approval, including canonical optional/proof shape.
     * @param supplied untrusted optional operation proof
     * @param current authenticated current configuration
     * @param selected validated old policy for this operation
     * @param request canonical operation envelope
     * @return whether canonical distinct old-key signatures meet the required old role
     */
    public static boolean approval(Optional<Proof> supplied, Ed25519Config current, ThresholdPolicy selected, IntentEnvelope request) {
        if (supplied.isEmpty()) return false;
        var proof = supplied.get();
        return Builtins.equalsData((PlutusData)(Object)supplied,
                (PlutusData)(Object)Optional.of(new Proof(BigInteger.ZERO, proof.signatures())))
                && Ed25519Lib.signatures(proof.signatures(), current.keys(), AccountLib.digest(request), 8)
                && Ed25519Lib.threshold(proof.signatures(), selected);
    }

    /**
     * Computes the canonical configuration-possession digest for the exact destination.
     * @param request full replacement intent
     * @param destination destination module reference
     * @param config complete new configuration
     * @return separate 32-byte possession digest
     */
    public static byte[] configMessage(IntentEnvelope request, AuthModuleRef destination, PlutusData config) {
        var preimage = new ConfigProofEnvelope(new byte[]{75,65,86,65,67,72,95,67,79,78,70,73,71,95,80,79,83,83,69,83,83,73,79,78,95,86,49},
                AccountLib.digest(request), destination, Builtins.blake2b_256(Builtins.serialiseData(config)));
        return Builtins.blake2b_256(Builtins.serialiseData((PlutusData)(Object)preimage));
    }

    /**
     * Requires possession of each key whose public bytes are not in the old validated registry.
     * Key retention is based on public bytes, not a reusable credential ID. Additional valid
     * target signatures may be supplied only when completion also needs a target threshold.
     * Verified evidence and destination IDs are merged once in strictly increasing order.
     * @param evidence sorted evidence under the destination registry
     * @param oldKeys previously validated registry
     * @param destination fully validated destination configuration
     * @param message configuration or recovery-target digest
     * @param targetThreshold whether to allow retained-key proofs and require target spend approval
     * @return whether all introduced keys are usable and the optional target threshold is met
     */
    public static boolean introduced(JulcList<Signature> evidence, JulcList<KeyEntry> oldKeys,
            Ed25519Config destination, byte[] message, boolean targetThreshold) {
        if (!evidence.isEmpty() && !Ed25519Lib.signatures(evidence, destination.keys(), message, 16)) return false;
        int introducedCount = introducedKeys(oldKeys, destination.keys(), evidence);
        return introducedCount >= 0 && (targetThreshold ? Ed25519Lib.threshold(evidence, destination.spend())
                : AccountLib.listSize((JulcList<PlutusData>)(Object)evidence) == introducedCount);
    }

    /** Pure recursive merge preserves the original evidence for final count/threshold checks. */
    static int introducedKeys(JulcList<KeyEntry> oldKeys, JulcList<KeyEntry> destination, JulcList<Signature> evidence) {
        if (destination.isEmpty()) return 0;
        var candidate = destination.head();
        boolean retained = Ed25519Lib.publicKeyKnown(candidate.publicKey(), oldKeys);
        boolean proven = !evidence.isEmpty() && evidence.head().credentialId().equals(candidate.credentialId());
        if (!retained && !proven) return -1;
        JulcList<Signature> rest = proven ? evidence.tail() : evidence;
        int remaining = introducedKeys(oldKeys, destination.tail(), rest);
        if (remaining < 0) return -1;
        return remaining + (retained ? 0 : 1);
    }

    /**
     * Validates a new module's configuration and all registry keys. Performs no old-role approval.
     * @param invocation candidate invocation with absent operation proof
     * @param replacement exact module replacement action
     * @return whether every candidate key proves possession under the installation domain
     */
    public static boolean candidate(ModuleRedeemer invocation, ReplaceModule replacement) {
        if (!Builtins.equalsData((PlutusData)(Object)invocation.operationProof(), (PlutusData)(Object)Optional.empty())) return false;
        var config = (Ed25519Config)(Object)replacement.newConfig();
        return Ed25519Lib.configuration(config)
                && AccountLib.listSize((JulcList<PlutusData>)(Object)invocation.configPossession()) == AccountLib.listSize((JulcList<PlutusData>)(Object)config.keys())
                && Ed25519Lib.signatures(invocation.configPossession(), config.keys(),
                    configMessage(invocation.intent(), replacement.newModule(), replacement.newConfig()), 16);
    }

    /**
     * Applies old-module role and target checks; immutable core separately enforces lifecycle.
     * @param invocation canonical invocation at the currently installed module
     * @param previous authenticated consumed old state
     * @return whether exactly the required old-role or committed-target authorization holds
     */
    public static boolean current(ModuleRedeemer invocation, AccountState previous) {
        var config = (Ed25519Config)(Object)previous.authConfig();
        if (!Ed25519Lib.configuration(config)) return false;
        boolean completing = switch (invocation.intent().action()) { case CompleteRecovery finish -> true; default -> false; };
        if (!completing && !roleApproval(invocation, config)) return false;
        return switch (invocation.intent().action()) {
            case Spend spend -> invocation.configPossession().isEmpty();
            case TransferWholeUtxo whole -> invocation.configPossession().isEmpty();
            case ReplaceConfig replacement -> {
                var target = (Ed25519Config)(Object)replacement.newConfig();
                yield Ed25519Lib.configuration(target)
                        && introduced(invocation.configPossession(), config.keys(), target,
                            configMessage(invocation.intent(), previous.authModule(), replacement.newConfig()), false);
            }
            case ReplaceModule replacement -> invocation.configPossession().isEmpty();
            case Freeze freeze -> invocation.configPossession().isEmpty();
            case Unfreeze unfreeze -> invocation.configPossession().isEmpty();
            case StartRecovery start -> invocation.configPossession().isEmpty()
                    && Ed25519Lib.configuration((Ed25519Config)(Object)start.replacementConfig());
            case CancelRecovery cancel -> invocation.configPossession().isEmpty();
            case CompleteRecovery completion -> {
                boolean absent = Builtins.equalsData((PlutusData)(Object)invocation.operationProof(), (PlutusData)(Object)Optional.empty());
                yield absent && switch (previous.mode()) {
                    case RecoveryPending pending -> {
                        var target = (Ed25519Config)(Object)completion.replacementConfig();
                        var preimage = new TargetProofEnvelope(new byte[]{75,65,86,65,67,72,95,82,69,67,79,86,69,82,89,95,84,65,82,71,69,84,95,86,49},
                                invocation.intent().domain(), previous.recoverySequence(), pending.proposalCommitment(),
                                Builtins.blake2b_256(Builtins.serialiseData(completion.replacementConfig())));
                        yield completion.currentSequence().equals(previous.recoverySequence())
                                && Builtins.equalsData(completion.replacementConfig(), pending.targetConfig())
                                && Ed25519Lib.configuration(target)
                                && introduced(invocation.configPossession(), config.keys(), target,
                                    Builtins.blake2b_256(Builtins.serialiseData((PlutusData)(Object)preimage)), true);
                    }
                    default -> false;
                };
            }
            default -> false;
        };
    }
    /** Selects one authenticated old role, then verifies its evidence exactly once. */
    static boolean roleApproval(ModuleRedeemer invocation, Ed25519Config currentConfig) {
        ThresholdPolicy selected = switch (invocation.intent().action()) {
            case Spend spend -> currentConfig.spend();
            case TransferWholeUtxo whole -> currentConfig.spend();
            case ReplaceConfig replacement -> currentConfig.admin();
            case ReplaceModule replacement -> currentConfig.admin();
            case Freeze freeze -> currentConfig.freeze();
            case Unfreeze unfreeze -> currentConfig.unfreeze();
            case StartRecovery start -> currentConfig.recovery();
            case CancelRecovery cancel -> currentConfig.cancel();
            default -> (ThresholdPolicy)(Object)Builtins.error();
        };
        return approval(invocation.operationProof(), currentConfig, selected, invocation.intent());
    }
}
