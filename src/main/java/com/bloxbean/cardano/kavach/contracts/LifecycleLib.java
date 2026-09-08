package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import java.math.BigInteger;

/**
 * Immutable V1 lifecycle semantics. Computes the sole permitted successor from an
 * authenticated old state and canonical intent. This library does not verify signatures,
 * NFT custody or transaction outputs: the state validator must compose those checks.
 * Timings use the effective ledger interval, never a clock supplied by the transaction builder.
 */
@OnchainLibrary
public class LifecycleLib {
    /**
     * Recovery commitment preimage in the six-field normative wire order.
     * @param intentDomain complete initiation domain, including consumed state reference
     * @param nextSequence authoritative incremented attempt number
     * @param module unchanged installed authorization module
     * @param replacementConfig complete proposed configuration
     * @param delay immutable old recovery delay
     * @param cooldown immutable old cooldown
     */
    public record ProposalEnvelope(IntentDomain intentDomain, BigInteger nextSequence,
            AuthModuleRef module, PlutusData replacementConfig, BigInteger delay, BigInteger cooldown) {}

    /**
     * Validates all lifecycle datum fields without interpreting opaque module configuration.
     * @param previous untrusted state datum
     * @param deployment immutable expected deployment
     * @param custodyHash immutable expected state-validator hash
     * @return whether exact state shape, timings and mode fields satisfy V1 bounds
     */
    public static boolean stateShape(AccountState previous, DeploymentDomain deployment, byte[] custodyHash) {
        if (!AccountLib.stateFields(previous, deployment, custodyHash)) return false;
        var bytes = Builtins.lengthOfByteString(Builtins.serialiseData((PlutusData)(Object)previous));
        return switch (previous.mode()) {
            case Normal normal -> AccountLib.shape((PlutusData)(Object)normal, 0, 0) && bytes <= 1536;
            case Frozen frozen -> AccountLib.shape((PlutusData)(Object)frozen, 1, 0) && bytes <= 1536;
            case RecoveryPending pending -> AccountLib.shape((PlutusData)(Object)pending, 2, 3)
                    && AccountLib.lessThan(BigInteger.ZERO, previous.recoverySequence())
                    && Builtins.lengthOfByteString(pending.proposalCommitment()) == 32
                    && AccountLib.uint63(pending.executeAfter())
                    && Builtins.lengthOfByteString(Builtins.serialiseData(pending.targetConfig())) <= 1024
                    && bytes <= 3072;
        };
    }

    /**
     * Computes a bounded sum or raises a script error; no saturation or integer wraparound.
     * @param left nonnegative bounded operand
     * @param right nonnegative bounded operand
     * @return exact sum in 0 through 2^63-1
     */
    public static BigInteger checkedAdd(BigInteger left, BigInteger right) {
        var result = left.add(right);
        if (!AccountLib.uint63(left) || !AccountLib.uint63(right) || !AccountLib.uint63(result))
            return (BigInteger)(Object)Builtins.error();
        return result;
    }

    /**
     * Derives the initiation commitment from authenticated fields, never a caller-selected hash.
     * @param previous authenticated old state
     * @param request canonical initiation envelope
     * @param attempt exact proposed incremented sequence
     * @param target complete target configuration
     * @return 32-byte canonical proposal commitment
     */
    public static byte[] proposal(AccountState previous, IntentEnvelope request, BigInteger attempt, PlutusData target) {
        var preimage = new ProposalEnvelope(request.domain(), attempt, previous.authModule(), target,
                previous.recoveryDelayMillis(), previous.recoveryCooldownMillis());
        return Builtins.blake2b_256(Builtins.serialiseData((PlutusData)(Object)preimage));
    }

    /**
     * Selects the sole permitted successor; unsupported modes/actions or malformed fields error.
     * Callers must first authenticate old state/domain and validate the envelope/ledger interval.
     * Configuration validity and authority remain mandatory independent module checks.
     * @param previous authenticated state consumed by this transition
     * @param request canonical envelope bound to that old state
     * @param ctx ledger context with finite interval already checked against the signed window
     * @return exact required state successor, including incremented version and retained fields
     */
    public static AccountState successor(AccountState previous, IntentEnvelope request, ScriptContext ctx) {
        BigInteger lower = switch (ctx.txInfo().validRange().from().boundType()) {
            case IntervalBoundType.Finite finite -> finite.time();
            default -> BigInteger.valueOf(-1);
        };
        BigInteger upper = switch (ctx.txInfo().validRange().to().boundType()) {
            case IntervalBoundType.Finite finite -> finite.time();
            default -> BigInteger.valueOf(-1);
        };
        if (!AccountLib.envelope(request, ctx) || !AccountLib.uint63(lower) || !AccountLib.uint63(upper))
            return (AccountState)(Object)Builtins.error();
        var version = checkedAdd(previous.stateVersion(), BigInteger.ONE);
        boolean normal = Builtins.equalsData((PlutusData)(Object)previous.mode(), (PlutusData)(Object)new Normal());
        boolean frozen = Builtins.equalsData((PlutusData)(Object)previous.mode(), (PlutusData)(Object)new Frozen());
        return switch (request.action()) {
            case ReplaceConfig change -> {
                if (!normal || !AccountLib.shape((PlutusData)(Object)change, 1, 1))
                    yield (AccountState)(Object)Builtins.error();
                yield copy(previous, version, previous.authModule(), change.newConfig(), previous.recoverySequence(),
                        previous.recoveryNotBefore(), new Normal());
            }
            case ReplaceModule change -> {
                if (!normal || !AccountLib.shape((PlutusData)(Object)change, 2, 2)
                        || !AccountLib.moduleShape(change.newModule())
                        || Builtins.equalsByteString(change.newModule().scriptHash(), previous.authModule().scriptHash())
                        || Builtins.equalsByteString(change.newModule().scriptHash(), previous.coreBinding().checkpoint()))
                    yield (AccountState)(Object)Builtins.error();
                yield copy(previous, version, change.newModule(), change.newConfig(), previous.recoverySequence(),
                        previous.recoveryNotBefore(), new Normal());
            }
            case Freeze freeze -> {
                if (!normal || !AccountLib.shape((PlutusData)(Object)freeze, 3, 0))
                    yield (AccountState)(Object)Builtins.error();
                yield copy(previous, version, previous.authModule(), previous.authConfig(), previous.recoverySequence(),
                        previous.recoveryNotBefore(), new Frozen());
            }
            case Unfreeze unfreeze -> {
                if (!frozen || !AccountLib.shape((PlutusData)(Object)unfreeze, 4, 0))
                    yield (AccountState)(Object)Builtins.error();
                yield copy(previous, version, previous.authModule(), previous.authConfig(), previous.recoverySequence(),
                        previous.recoveryNotBefore(), new Normal());
            }
            case StartRecovery start -> {
                var sequence = checkedAdd(previous.recoverySequence(), BigInteger.ONE);
                if ((!normal && !frozen) || !AccountLib.shape((PlutusData)(Object)start, 5, 2)
                        || !start.nextSequence().equals(sequence) || AccountLib.lessThan(lower, previous.recoveryNotBefore()))
                    yield (AccountState)(Object)Builtins.error();
                var deadline = checkedAdd(upper, previous.recoveryDelayMillis());
                var nextTime = extendCooldown(previous, upper);
                var pending = new RecoveryPending(proposal(previous, request, sequence, start.replacementConfig()),
                        deadline, start.replacementConfig());
                yield copy(previous, version, previous.authModule(), previous.authConfig(), sequence, nextTime, pending);
            }
            case CancelRecovery cancel -> {
                boolean allowed = switch (previous.mode()) {
                    case RecoveryPending pending -> cancel.currentSequence().equals(previous.recoverySequence())
                            && Builtins.equalsByteString(cancel.proposalCommitment(), pending.proposalCommitment());
                    default -> false;
                };
                if (!allowed || !AccountLib.shape((PlutusData)(Object)cancel, 6, 2))
                    yield (AccountState)(Object)Builtins.error();
                yield copy(previous, version, previous.authModule(), previous.authConfig(), previous.recoverySequence(),
                        extendCooldown(previous, upper), new Frozen());
            }
            case CompleteRecovery complete -> {
                boolean allowed = switch (previous.mode()) {
                    case RecoveryPending pending -> complete.currentSequence().equals(previous.recoverySequence())
                            && AccountLib.atMost(pending.executeAfter(), lower)
                            && Builtins.equalsData(complete.replacementConfig(), pending.targetConfig());
                    default -> false;
                };
                if (!allowed || !AccountLib.shape((PlutusData)(Object)complete, 7, 2))
                    yield (AccountState)(Object)Builtins.error();
                yield copy(previous, version, previous.authModule(), complete.replacementConfig(), previous.recoverySequence(),
                        previous.recoveryNotBefore(), new Normal());
            }
            default -> (AccountState)(Object)Builtins.error();
        };
    }

    /** Extends the core-managed deadline monotonically, using the conservative upper endpoint. */
    static BigInteger extendCooldown(AccountState previous, BigInteger endpoint) {
        var candidate = checkedAdd(endpoint, previous.recoveryCooldownMillis());
        return AccountLib.lessThan(candidate, previous.recoveryNotBefore()) ? previous.recoveryNotBefore() : candidate;
    }

    /** Rebuilds every state field explicitly so no transition can accidentally mutate immutable fields. */
    static AccountState copy(AccountState previous, BigInteger version, AuthModuleRef module, PlutusData config,
            BigInteger sequence, BigInteger nextTime, AccountMode nextMode) {
        return new AccountState(previous.schemaVersion(), previous.accountId(), previous.deploymentDomain(),
                previous.coreBinding(), version, module, config, sequence, previous.recoveryDelayMillis(),
                previous.recoveryCooldownMillis(), nextTime, nextMode);
    }
}
