package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Usage;
import java.math.BigInteger;

/**
 * Creator-bound one-shot unused-counter initialization. Parameters: seed, creator,
 * budgetValidatorHash. The validator hash commits the deployment and account identity.
 * Minting alone does not enable a budget: old account admin must install the exact NFT ID.
 * Burning and counter-deposit refunds are unsupported in this development candidate.
 */
@MintingValidator
public class PeriodicBudgetNftPolicy {
    @Param static TxOutRef seed;
    @Param static byte[] creator;
    @Param static byte[] budgetValidatorHash;

    /** Creates exactly one canonical unused counter under its predetermined script custody. */
    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        if (!redeemer.equals(BigInteger.ZERO) || !AccountLib.transactionShape(ctx)) return false;
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript minting -> {
                var own = Builtins.toByteString(minting.policyId());
                boolean signed = false; boolean consumed = false; boolean valid = true;
                for (var signer : ctx.txInfo().signatories()) if (Builtins.equalsByteString(signer.hash(), creator)) signed = true;
                for (var input : ctx.txInfo().inputs()) {
                    boolean key = switch (input.resolved().address().credential()) { case Credential.PubKeyCredential credential -> true; default -> false; };
                    if (!key) valid = false;
                    if (input.outRef().equals(seed)) consumed = switch (input.resolved().address().credential()) {
                        case Credential.PubKeyCredential credential -> Builtins.equalsByteString(credential.hash().hash(), creator);
                        default -> false;
                    };
                }
                int matches = 0;
                for (var output : ctx.txInfo().outputs()) {
                    if (!ValuesLib.assetOf(output.value(), own, new byte[]{}).equals(BigInteger.ZERO)) {
                        matches = matches + 1;
                        boolean datum = switch (output.datum()) {
                            case OutputDatum.OutputDatumInline inline -> Builtins.equalsData(inline.datum(),
                                    (PlutusData)(Object)PeriodicBudgetLib.initial());
                            default -> false;
                        };
                        if (!datum || !output.address().equals(AccountLib.enterprise(budgetValidatorHash)) || output.referenceScript().isPresent()
                                || AccountLib.assetCount(output.value()) != 2
                                || !ValuesLib.assetOf(output.value(), own, new byte[]{}).equals(BigInteger.ONE)
                                || !AccountLib.uint63(ValuesLib.lovelaceOf(output.value()))
                                || AccountLib.atMost(ValuesLib.lovelaceOf(output.value()), BigInteger.ZERO)) valid = false;
                    }
                }
                yield valid && signed && consumed && matches == 1 && AccountLib.assetCount(ctx.txInfo().mint()) == 1
                        && ValuesLib.assetOf(ctx.txInfo().mint(), own, new byte[]{}).equals(BigInteger.ONE);
            }
            default -> false;
        };
    }
}
