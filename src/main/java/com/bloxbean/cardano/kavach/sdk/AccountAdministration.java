package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.RecoveryModel;
import com.bloxbean.cardano.kavach.protocol.WireFormat;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Canonical off-chain preparation for administration and recovery. This class computes
 * the expected datum; it does not authenticate ledger state, verify signing authority,
 * or submit a transaction. Callers must resolve the current state NFT independently and
 * evaluate the complete transaction with all mandatory state/core/module validators.
 */
public final class AccountAdministration {
    private AccountAdministration() {
    }

    /**
     * Canonical matching mutation witnesses after SDK checks. This is off-chain evidence,
     * not a ledger receipt; the transaction must still satisfy every compiled validator.
     *
     * @param successor exact next datum
     * @param core      common state/core invocation
     * @param current   current module invocation with verified old-role or recovery-target proofs
     * @param candidate optional separately verified candidate invocation
     */
    public record Prepared(AccountState successor, CoreRedeemer core, ModuleRedeemer current,
                           Optional<ModuleRedeemer> candidate) {
    }

    /**
     * Checks lifecycle and all raw-Ed25519 evidence before returning matching invocations.
     * CompleteRecovery uses target proofs; it never requires the lost old spend key.
     * Ledger custody and complete positive-reward balances are checked during attachment.
     *
     * @param previous       authenticated old first-module state
     * @param current        complete current-module invocation
     * @param candidate      candidate invocation, present only for module replacement
     * @param lower          effective finite ledger lower bound in POSIX milliseconds
     * @param upper          effective finite ledger upper bound in POSIX milliseconds
     * @param upperInclusive whether the ledger includes the upper endpoint
     * @return canonical successor and coordinated invocations
     * @throws IllegalArgumentException for invalid lifecycle, configuration, bindings or proof evidence
     */
    public static Prepared prepare(AccountState previous, ModuleRedeemer current, Optional<ModuleRedeemer> candidate,
                                   BigInteger lower, BigInteger upper, boolean upperInclusive) {
        var next = successor(previous, current.intent(), lower, upper, upperInclusive);
        AdministrationProofs.verify(previous, current, candidate);
        return new Prepared(next, new CoreRedeemer(BigInteger.ONE, current.intent(), current.receipts()), current, candidate);
    }

    /**
     * Computes the exact successor for a state mutation using the effective ledger time
     * bounds, not the larger signed intent window. Bounds are POSIX milliseconds after
     * slot conversion. The upper bound is used conservatively for delay and cooldown even
     * when exclusive. Returning a datum does not establish operation or target approval.
     *
     * @param previous       complete resolved old state, whose ledger custody must be authenticated separately
     * @param request        canonical mutation intent bound to the old state and its consumed reference
     * @param lower          effective finite transaction lower time bound
     * @param upper          effective finite transaction upper time bound
     * @param upperInclusive whether the ledger includes the upper endpoint
     * @return exact next datum, preserving identity, immutable bindings and recovery timings
     * @throws IllegalArgumentException for unsupported actions, wrong domains, lifecycle violations,
     *                                  target substitution, invalid configuration, interval violations or counter/time overflow
     */
    public static AccountState successor(AccountState previous, IntentEnvelope request,
                                         BigInteger lower, BigInteger upper, boolean upperInclusive) {
        var stateData = AccountCodec.data(previous);
        var intentData = AccountCodec.data(request);
        WireFormat.renderSigningRequest(intentData, stateData, request.domain().stateRef().toPlutusData(), null);
        require(lower.compareTo(request.validity().notBefore()) >= 0
                && (upper.compareTo(request.validity().expiresAt()) < 0
                || (!upperInclusive && upper.equals(request.validity().expiresAt()))), "Ledger interval outside signed window");
        var interval = new RecoveryModel.Interval(lower, upper);
        RecoveryModel.Mode oldMode = switch (previous.mode()) {
            case Normal ignored -> RecoveryModel.Mode.NORMAL;
            case Frozen ignored -> RecoveryModel.Mode.FROZEN;
            case RecoveryPending ignored -> RecoveryModel.Mode.PENDING;
        };
        var pending = previous.mode() instanceof RecoveryPending value ? value : null;
        var old = new RecoveryModel.State(previous.stateVersion(), previous.recoverySequence(), previous.recoveryNotBefore(),
                previous.recoveryDelayMillis(), previous.recoveryCooldownMillis(), oldMode,
                pending == null ? null : HexFormat.of().formatHex(pending.proposalCommitment()),
                pending == null ? null : pending.executeAfter());
        var module = previous.authModule();
        var config = previous.authConfig();
        var target = config;
        String commitment = old.commitment();
        RecoveryModel.Action action;
        RecoveryModel.Authority role;
        switch (request.action()) {
            case ReplaceConfig change -> {
                action = RecoveryModel.Action.CONFIGURE;
                role = RecoveryModel.Authority.ADMIN;
                config = change.newConfig();
            }
            case ReplaceModule change -> {
                action = RecoveryModel.Action.CONFIGURE;
                role = RecoveryModel.Authority.ADMIN;
                require(!Arrays.equals(change.newModule().scriptHash(), module.scriptHash())
                        && !Arrays.equals(change.newModule().scriptHash(), previous.coreBinding().checkpoint()), "Module credentials must be distinct");
                module = change.newModule();
                config = change.newConfig();
            }
            case Freeze ignored -> {
                action = RecoveryModel.Action.FREEZE;
                role = RecoveryModel.Authority.FREEZE;
            }
            case Unfreeze ignored -> {
                action = RecoveryModel.Action.UNFREEZE;
                role = RecoveryModel.Authority.UNFREEZE;
            }
            case StartRecovery start -> {
                action = RecoveryModel.Action.START;
                role = RecoveryModel.Authority.RECOVERY;
                commitment = HexFormat.of().formatHex(WireFormat.digest(ProofDomains.recoveryCommitment(stateData, intentData)));
                target = start.replacementConfig();
            }
            case CancelRecovery cancel -> {
                action = RecoveryModel.Action.CANCEL;
                role = RecoveryModel.Authority.CANCEL;
                require(cancel.currentSequence().equals(previous.recoverySequence()), "Recovery sequence mismatch");
                commitment = HexFormat.of().formatHex(cancel.proposalCommitment());
            }
            case CompleteRecovery complete -> {
                action = RecoveryModel.Action.COMPLETE;
                role = RecoveryModel.Authority.TARGET;
                require(pending != null && complete.currentSequence().equals(previous.recoverySequence())
                        && complete.replacementConfig().equals(pending.targetConfig()), "Recovery target or sequence mismatch");
                config = complete.replacementConfig();
            }
            default -> throw new IllegalArgumentException("Administration requires a state mutation action");
        }
        // Role here classifies the requested action for the model; no signatures are implied.
        var next = RecoveryModel.apply(old, action, role, interval, commitment);
        AccountMode mode = switch (next.mode()) {
            case NORMAL -> new Normal();
            case FROZEN -> new Frozen();
            case PENDING ->
                    new RecoveryPending(HexFormat.of().parseHex(next.commitment()), next.executeAfter(), target);
        };
        var result = new AccountState(previous.schemaVersion(), previous.accountId(), previous.deploymentDomain(),
                previous.coreBinding(), next.version(), module, config, next.sequence(), previous.recoveryDelayMillis(),
                previous.recoveryCooldownMillis(), next.notBefore(), mode);
        WireFormat.validateState(AccountCodec.data(result));
        return result;
    }

    /**
     * Prepares a mutation under explicit authenticated browser profiles. Transaction mode
     * verifies the planned required-signers set; actual witness verification belongs to the ledger.
     */
    public static Prepared prepare(AccountState previous, ModuleRedeemer current, Optional<ModuleRedeemer> candidate,
                                   BigInteger lower, BigInteger upper, boolean upperInclusive, BrowserAuthorization authorization) {
        var next = successor(previous, current.intent(), lower, upper, upperInclusive);
        authorization.verify(previous, current, candidate);
        return new Prepared(next, new CoreRedeemer(BigInteger.ONE, current.intent(), current.receipts()), current, candidate);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
