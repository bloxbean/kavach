package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

/** Permanent, disposable DevKit state holder. No spend, recovery or cleanup path. */
@SpendingValidator
public class SealedStateProbe {
    /**
     * Rejects every attempt to spend the disposable state output, including cleanup.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param datum untrusted datum, ignored by the permanently sealed fixture
     * @param redeemer untrusted fixture redeemer; not the Kavach account ABI
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return false;
    }
}
