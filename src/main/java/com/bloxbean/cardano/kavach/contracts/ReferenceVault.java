package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;

import java.math.BigInteger;

/**
 * Publisher-owned custody for reference-script capital, separate from account authority.
 * Parameters in application order are publisher payment-key hash, fixed ASCII purpose
 * {@code kavach-acc-ref}, and the full account NFT identifier. No datum is required.
 * The publisher may reclaim active references or mistaken deposits without replacement;
 * this can interrupt account availability and does not authorize spending account assets.
 */
@SpendingValidator
public class ReferenceVault {
    @Param static byte[] publisherPaymentKeyHash;
    @Param static byte[] purpose;
    @Param static AccountId accountId;

    /** Explicit reclamation request: constructor zero, schema version one, full account identity. */
    public record Reclaim(BigInteger schemaVersion, AccountId accountId) { }

    /**
     * Requires the publisher's ledger-verified required signatory and strict versioned request.
     * No recipient, datum, reference-script presence or continuing-output condition is imposed.
     *
     * @param redeemer untrusted reclamation request
     * @param ctx ledger-supplied transaction context
     * @return whether the owner-authorized spending request is valid
     */
    @Entrypoint
    public static boolean validate(Reclaim redeemer, ScriptContext ctx) {
        boolean spending = switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript own -> true;
            default -> false;
        };
        return spending
                && Builtins.lengthOfByteString(publisherPaymentKeyHash) == 28
                && Builtins.equalsByteString(purpose, new byte[]{107, 97, 118, 97, 99, 104, 45, 97, 99, 99, 45, 114, 101, 102})
                && AccountLib.shape((PlutusData) (Object) accountId, 0, 2)
                && Builtins.lengthOfByteString(accountId.policy()) == 28
                && Builtins.lengthOfByteString(accountId.name()) <= 32
                && AccountLib.shape((PlutusData) (Object) redeemer, 0, 2)
                && redeemer.schemaVersion().equals(BigInteger.ONE)
                && Builtins.equalsData((PlutusData) (Object) redeemer.accountId(), (PlutusData) (Object) accountId)
                && ctx.txInfo().signatories().contains(new PubKeyHash(publisherPaymentKeyHash));
    }
}
