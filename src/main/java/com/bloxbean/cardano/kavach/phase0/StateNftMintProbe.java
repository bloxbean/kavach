package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;

import java.math.BigInteger;

/**
 * One-shot identity experiment, not the V1 creation policy/configuration ABI.
 * <p>Immutable parameter order: {@code seed, creator, stateValidatorHash, domain}.</p>
 */
@MintingValidator
public class StateNftMintProbe {
    @Param
    static TxOutRef seed;
    @Param
    static byte[] creator;
    @Param
    static byte[] stateValidatorHash;
    @Param
    static byte[] domain;

    public record ProbeState(BigInteger schemaVersion, byte[] domain, byte[] creator) {
    }

    /**
     * Checks creator authority and seed consumption for one-shot fixture NFT creation.
     * This isolated fixture does not authorize a complete Kavach account operation.
     *
     * @param redeemer untrusted fixture redeemer; not the Kavach account ABI
     * @param ctx      ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        if (redeemer != BigInteger.ZERO) return false;
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript minting -> initialize(ctx, minting);
            default -> false;
        };
    }

    static boolean initialize(ScriptContext ctx, ScriptInfo.MintingScript minting) {
        var tx = ctx.txInfo();
        boolean signed = false;
        for (var signer : tx.signatories()) {
            if (Builtins.equalsByteString(signer.hash(), creator)) signed = true;
        }
        if (!signed) return false;
        boolean seedConsumed = false;
        for (var input : tx.inputs()) {
            if (input.outRef().equals(seed)) {
                seedConsumed = switch (input.resolved().address().credential()) {
                    case Credential.PubKeyCredential key -> Builtins.equalsByteString(key.hash().hash(), creator);
                    default -> false;
                };
            }
        }
        if (!seedConsumed) return false;
        var policy = minting.policyId();
        // Isolated creation transaction: exactly one mint entry, the fixed empty name.
        if (ValuesLib.flattenTyped(tx.mint()).size() != 1) return false;
        if (tx.mint().assetOf(policy, new TokenName(new byte[]{})) != BigInteger.ONE) return false;
        var expected = new ProbeState(BigInteger.ZERO, domain, creator);
        BigInteger matches = BigInteger.ZERO;
        boolean validState = true;
        for (var output : tx.outputs()) {
            if (output.value().assetOf(policy, new TokenName(new byte[]{})) != BigInteger.ZERO) {
                boolean addressMatches = switch (output.address().credential()) {
                    case Credential.ScriptCredential script ->
                            Builtins.equalsByteString(script.hash().hash(), stateValidatorHash);
                    default -> false;
                };
                boolean datumMatches = switch (output.datum()) {
                    case OutputDatum.OutputDatumInline inline ->
                            Builtins.equalsData(inline.datum(), (PlutusData) (Object) expected);
                    default -> false;
                };
                if (addressMatches && datumMatches && output.address().stakingCredential().isEmpty()
                        && output.referenceScript().isEmpty() && ValuesLib.flattenTyped(output.value()).size() == 2
                        && output.value().assetOf(policy, new TokenName(new byte[]{})) == BigInteger.ONE
                        && ValuesLib.assetOf(output.value(), new byte[]{}, new byte[]{}).signum() > 0) {
                    matches = matches.add(BigInteger.ONE);
                } else {
                    validState = false;
                }
            }
        }
        return validState && matches == BigInteger.ONE;
    }
}
