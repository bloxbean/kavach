package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;

import java.math.BigInteger;

/**
     * Authenticates immutable genesis state by the complete NFT identity and address. 
 * <p>Immutable parameter order: {@code nftPolicy, stateValidatorHash, domain, creator}.</p>
 */
@WithdrawValidator
public class StateReferenceProbe {
    @Param static byte[] nftPolicy;
    @Param static byte[] stateValidatorHash;
    @Param static byte[] domain;
    @Param static byte[] creator;

    public record ProbeState(BigInteger schemaVersion, byte[] domain, byte[] creator) {}

    /**
     * Checks the complete fixture NFT identity, custody address and inline probe datum.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param redeemer untrusted fixture redeemer; not the Kavach account ABI
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        if (redeemer != BigInteger.ZERO) return false;
        boolean rewarding = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> ctx.txInfo().withdrawals().containsKey(reward.credential());
            default -> false;
        };
        if (!rewarding || !ValuesLib.isZero(ctx.txInfo().mint())) return false;
        boolean signed = false;
        for (var signer : ctx.txInfo().signatories()) {
            if (Builtins.equalsByteString(signer.hash(), creator)) signed = true;
        }
        if (!signed) return false;
        var expected = new ProbeState(BigInteger.ZERO, domain, creator);
        var policy = new PolicyId(nftPolicy);
        BigInteger matches = BigInteger.ZERO;
        boolean validState = true;
        for (var input : ctx.txInfo().referenceInputs()) {
            var output = input.resolved();
            if (output.value().assetOf(policy, new TokenName(new byte[]{})) != BigInteger.ZERO) {
                boolean addressMatches = switch (output.address().credential()) {
                    case Credential.ScriptCredential script -> Builtins.equalsByteString(script.hash().hash(), stateValidatorHash);
                    default -> false;
                };
                boolean datumMatches = switch (output.datum()) {
                    case OutputDatum.OutputDatumInline inline -> Builtins.equalsData(inline.datum(), (PlutusData) (Object) expected);
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
