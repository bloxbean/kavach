package com.bloxbean.cardano.kavach.auth.browser;

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
 * Browser-wallet authorization module with an immutable evidence mode (1: transaction witnesses; 2: COSE). Independently authenticates canonical state,
 * resolves configured keys and binds the entire intent/receipt table to the immutable core.
 * Genesis requires all-key possession over a separate domain. State mutations use old-role
 * approval and independently domain-separated successor or recovery-target possession.
 * Parameters are applied in deployment.md order; no signer key is a script parameter.
 * <p>Immutable parameters, applied from left to right: {@code moduleVersion, abiVersion, deploymentDomain, stateValidatorHash, coreCheckpointHash, rewardSink, signingMode}.</p>
 * <p>Entrypoints are compiled to UPLC. A false result or script evaluation error
 * rejects the transaction; invoking these Java methods directly is not a ledger test.</p>
 */
@MultiValidator
public class BrowserModule {
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
    static BigInteger signingMode;

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
        if (!moduleVersion.equals(BigInteger.ONE) || !abiVersion.equals(BigInteger.ONE)
                || (!signingMode.equals(BigInteger.ONE) && !signingMode.equals(BigInteger.TWO))) return false;
        return switch (invocation) {
            case ModuleRedeemer operation -> switch (operation.intent().action()) {
                case Spend spend -> authorize(operation, ctx);
                case TransferWholeUtxo whole -> authorize(operation, ctx);
                default -> mutate(operation, ctx);
            };
            case GenesisModuleRedeemer genesis -> initialize(genesis, ctx);
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
                || ctx.txInfo().withdrawals().size() != 2 || !(AccountLib.assetCount(ctx.txInfo().mint()) == 0))
            return false;
        var state = AccountLib.resolve(invocation.intent().domain(), ctx);
        if (!AccountLib.authenticate(state, invocation.intent().domain(), deploymentDomain, stateValidatorHash, ctx)
                || !Builtins.equalsByteString(state.coreBinding().checkpoint(), coreCheckpointHash)) return false;
        var own = AccountLib.script(state.authModule().scriptHash());
        if (!bound(invocation, state, own, ctx)) return false;
        return BrowserLifecycleLib.current(invocation, state, signingMode, deploymentDomain.networkId(), ctx);
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
        var config = (Ed25519Config) (Object) state.authConfig();
        if (!valid || matches != 1 || !Ed25519Lib.configuration(config) || AccountLib.listSize((JulcList<PlutusData>) (Object) genesis.configPossession()) != AccountLib.listSize((JulcList<PlutusData>) (Object) config.keys()))
            return false;
        var envelope = new GenesisProofEnvelope(new byte[]{75, 65, 86, 65, 67, 72, 95, 71, 69, 78, 69, 83, 73, 83, 95, 80, 79, 83, 83, 69, 83, 83, 73, 79, 78, 95, 86, 49},
                deploymentDomain, state.accountId(), state.coreBinding(), state.authModule(), Builtins.blake2b_256(Builtins.serialiseData(state.authConfig())));
        return BrowserEvidenceLib.signatures(genesis.configPossession(), config.keys(), Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) envelope)), 16, signingMode, deploymentDomain.networkId(), ctx);
    }

    /**
     * Independently authenticates old state; candidate validation cannot substitute for old authority.
     */
    static boolean mutate(ModuleRedeemer invocation, ScriptContext ctx) {
        if (!invocation.abiVersion().equals(BigInteger.ONE) || !AccountLib.envelope(invocation.intent(), ctx))
            return false;
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
                || !Builtins.equalsData(ctx.txInfo().redeemers().get(statePurpose), (PlutusData) (Object) coreInvocation))
            return false;
        return switch (invocation.intent().action()) {
            case ReplaceModule replacement -> {
                var candidate = AccountLib.script(replacement.newModule().scriptHash());
                yield AccountLib.moduleShape(replacement.newModule()) && !candidate.equals(oldCredential)
                        && !candidate.equals(AccountLib.script(coreCheckpointHash)) && ctx.txInfo().withdrawals().size() == 3
                        && ctx.txInfo().withdrawals().containsKey(oldCredential) && ctx.txInfo().withdrawals().containsKey(candidate)
                        && (own.equals(oldCredential) ? BrowserLifecycleLib.current(invocation, previous, signingMode, deploymentDomain.networkId(), ctx)
                        : own.equals(candidate) && BrowserLifecycleLib.candidate(invocation, replacement, signingMode, deploymentDomain.networkId(), ctx));
            }
            default -> ctx.txInfo().withdrawals().size() == 2 && own.equals(oldCredential)
                    && BrowserLifecycleLib.current(invocation, previous, signingMode, deploymentDomain.networkId(), ctx);
        };
    }

    /**
     * Shared exact purpose/envelope/receipt binding for both reference-state and consumed-state operations.
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
