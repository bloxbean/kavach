package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import java.math.BigInteger;

/** Ledger byte-priced output minimum, including the final lovelace integer encoding. */
final class OutputMinimum {
    private OutputMinimum() { }

    static BigInteger required(TransactionOutput output, ProtocolParams parameters) throws Exception {
        var price = new BigInteger(parameters.getCoinsPerUtxoSize());
        if (price.signum() <= 0) throw new IllegalArgumentException("Invalid minimum-ADA parameters");
        return price.multiply(BigInteger.valueOf(160L + CborSerializationUtil.serialize(output.serialize()).length));
    }

    /** Raises a freshly constructed output to a stable minimum, preserving any existing reserve. */
    static BigInteger fund(TransactionOutput output, ProtocolParams parameters) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            var minimum = required(output, parameters);
            if (output.getValue().getCoin().compareTo(minimum) >= 0) return output.getValue().getCoin();
            output.getValue().setCoin(minimum);
        }
        throw new IllegalArgumentException("Minimum ADA did not stabilize");
    }
}
