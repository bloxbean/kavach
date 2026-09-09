package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.CertifyingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;

import java.math.BigInteger;

/**
 * Controlled ledger fixture ONLY. Unlike Kavach, deliberately permits authorized deregistration.
 * <p>Immutable parameter order: {@code authority}.</p>
 */
@CertifyingValidator
public class DeregistrationFixture {
    @Param
    static byte[] authority;

    /**
     * Permits authority-signed deregistration in the disposable lifecycle experiment.
     * This isolated fixture does not authorize a complete Kavach account operation.
     *
     * @param redeemer untrusted fixture redeemer; not the Kavach account ABI
     * @param ctx      ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        if (redeemer != BigInteger.ZERO) return false;
        boolean unregistering = switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying -> switch (certifying.cert()) {
                case TxCert.UnRegStaking unreg -> true;
                default -> false;
            };
            default -> false;
        };
        boolean authorized = false;
        for (var signer : ctx.txInfo().signatories()) {
            if (Builtins.equalsByteString(signer.hash(), authority)) authorized = true;
        }
        return unregistering && authorized;
    }
}
