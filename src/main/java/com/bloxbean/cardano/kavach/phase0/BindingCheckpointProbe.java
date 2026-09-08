package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import java.math.BigInteger;

/**
     * Paired core/module invocation experiment; no account asset custody or state mutation. 
 * <p>Immutable parameter order: {@code role, deploymentDomain, accountPolicy, stateVersion, publicKey, installedModule, rewardSink, stateValidatorHash, creator, stateDomain}.</p>
 */
@WithdrawValidator
public class BindingCheckpointProbe {
    @Param static BigInteger role; // 0 = cryptographic module, 1 = calling core
    @Param static byte[] deploymentDomain;
    @Param static byte[] accountPolicy;
    @Param static BigInteger stateVersion;
    @Param static byte[] publicKey; // used only by module; both roles bind the same deployment
    @Param static byte[] installedModule; // empty in module, exact module hash in core
    @Param static Address rewardSink;
    @Param static byte[] stateValidatorHash;
    @Param static byte[] creator;
    @Param static byte[] stateDomain;

    public record ProbeState(BigInteger schemaVersion, byte[] domain, byte[] creator) {}

    public record Envelope(byte[] domain, byte[] account, BigInteger version, BigInteger operation,
                           TxOutRef consumedInput, byte[] coreHash, byte[] moduleHash) {}
    public record Authorization(Envelope intent, byte[] signature, BigInteger coreReceipt, BigInteger moduleReceipt) {}

    /**
     * Exercises shared core/module binding, state reference and reward receipt checks.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param authorization untrusted probe-specific evidence and declared inputs
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(Authorization authorization, ScriptContext ctx) {
        if (role != BigInteger.ZERO && role != BigInteger.ONE) return false;
        if (ctx.txInfo().inputs().size() > 16 || ctx.txInfo().outputs().size() > 16
                || ctx.txInfo().referenceInputs().size() > 4 || ctx.txInfo().withdrawals().size() != 2) return false;
        // This genesis-only probe has no mutable state version. Do not infer rotation support.
        if (stateVersion != BigInteger.ZERO) return false;
        var expectedState = new ProbeState(BigInteger.ZERO, stateDomain, creator);
        int matchingStates = 0;
        boolean validStates = true;
        for (var ref : ctx.txInfo().referenceInputs()) {
            var output = ref.resolved();
            if (ValuesLib.assetOf(output.value(), accountPolicy, new byte[]{}) != BigInteger.ZERO) {
                boolean holder = switch (output.address().credential()) {
                    case Credential.ScriptCredential script -> Builtins.equalsByteString(script.hash().hash(), stateValidatorHash);
                    default -> false;
                };
                boolean datum = switch (output.datum()) {
                    case OutputDatum.OutputDatumInline inline -> Builtins.equalsData(inline.datum(), (PlutusData) (Object) expectedState);
                    default -> false;
                };
                if (!holder || !datum || output.address().stakingCredential().isPresent() || output.referenceScript().isPresent()
                        || ValuesLib.flattenTyped(output.value()).size() != 2
                        || ValuesLib.assetOf(output.value(), accountPolicy, new byte[]{}) != BigInteger.ONE
                        || ValuesLib.assetOf(output.value(), new byte[]{}, new byte[]{}).signum() <= 0) validStates = false;
                matchingStates = matchingStates + 1;
            }
        }
        if (!validStates || matchingStates != 1) return false;
        var intent = authorization.intent();
        var canonicalIntent = new Envelope(intent.domain(), intent.account(), intent.version(), intent.operation(),
                intent.consumedInput(), intent.coreHash(), intent.moduleHash());
        var canonical = new Authorization(canonicalIntent, authorization.signature(), authorization.coreReceipt(), authorization.moduleReceipt());
        if (!Builtins.equalsData((PlutusData) (Object) authorization, (PlutusData) (Object) canonical)) return false;
        if (!Builtins.equalsByteString(intent.domain(), deploymentDomain) || !Builtins.equalsByteString(intent.account(), accountPolicy)
                || intent.version() != stateVersion || intent.operation() != BigInteger.ZERO) return false;
        if (Builtins.lengthOfByteString(intent.coreHash()) != 28 || Builtins.lengthOfByteString(intent.moduleHash()) != 28
                || Builtins.equalsByteString(intent.coreHash(), intent.moduleHash())) return false;
        var core = new Credential.ScriptCredential(new ScriptHash(intent.coreHash()));
        var module = new Credential.ScriptCredential(new ScriptHash(intent.moduleHash()));
        boolean own = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript rewarding -> role == BigInteger.ZERO
                    ? rewarding.credential().equals(module) : rewarding.credential().equals(core);
            default -> false;
        };
        if (!own || !ctx.txInfo().withdrawals().containsKey(core) || !ctx.txInfo().withdrawals().containsKey(module)) return false;
        if (role == BigInteger.ONE && !Builtins.equalsByteString(installedModule, intent.moduleHash())) return false;
        var corePurpose = new ScriptPurpose.Rewarding(core);
        var modulePurpose = new ScriptPurpose.Rewarding(module);
        if (!ctx.txInfo().redeemers().containsKey(corePurpose) || !ctx.txInfo().redeemers().containsKey(modulePurpose)) return false;
        if (!Builtins.equalsData(ctx.txInfo().redeemers().get(corePurpose), (PlutusData) (Object) canonical)
                || !Builtins.equalsData(ctx.txInfo().redeemers().get(modulePurpose), (PlutusData) (Object) canonical)) return false;
        boolean consumed = false;
        for (var input : ctx.txInfo().inputs()) {
            if (input.outRef().equals(intent.consumedInput())) consumed = true;
        }
        if (!consumed) return false;
        var coreAmount = ctx.txInfo().withdrawals().get(core);
        var moduleAmount = ctx.txInfo().withdrawals().get(module);
        if (!receipt(ctx, coreAmount, authorization.coreReceipt()) || !receipt(ctx, moduleAmount, authorization.moduleReceipt())) return false;
        if (coreAmount.signum() > 0 && moduleAmount.signum() > 0 && authorization.coreReceipt() == authorization.moduleReceipt()) return false;
        if (role == BigInteger.ZERO) return Builtins.verifyEd25519Signature(publicKey,
                Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) canonicalIntent)), authorization.signature());
        return true;
    }
    static boolean receipt(ScriptContext ctx, BigInteger amount, BigInteger index) {
        if (amount.signum() < 0) return false;
        if (amount == BigInteger.ZERO) return index == BigInteger.valueOf(-1);
        if (index.signum() < 0 || index.compareTo(BigInteger.valueOf(ctx.txInfo().outputs().size())) >= 0) return false;
        var output = ctx.txInfo().outputs().get(index.intValue());
        boolean noDatum = switch (output.datum()) {
            case OutputDatum.NoOutputDatum none -> true;
            default -> false;
        };
        return output.address().equals(rewardSink) && noDatum && output.referenceScript().isEmpty()
                && ValuesLib.flattenTyped(output.value()).size() == 1
                && ValuesLib.assetOf(output.value(), new byte[]{}, new byte[]{}).compareTo(amount) >= 0;
    }
}
