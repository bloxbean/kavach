package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import java.math.BigInteger;

/**
     * Disposable devnet asset holder for the whole-value experiment, not a Kavach asset validator. 
 * <p>Immutable parameter order: {@code authority}.</p>
 */
@SpendingValidator
public class OwnedAssetFixture {
    @Param static byte[] authority;
    /**
     * Allows the parameterized authority to spend disposable experiment assets.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param redeemer untrusted fixture redeemer; not the Kavach account ABI
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        boolean spending = switch (ctx.scriptInfo()) { case ScriptInfo.SpendingScript spend -> true; default -> false; };
        boolean signed = false;
        for (var signer : ctx.txInfo().signatories()) if (Builtins.equalsByteString(signer.hash(), authority)) signed = true;
        return spending && signed && redeemer == BigInteger.ZERO;
    }
}
