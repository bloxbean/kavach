package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SponsorFeeProtectionTest {
    @Test
    void failsClosedIfBalancingChangesOutputLayout() {
        String address = new Account(Networks.testnet()).baseAddress();
        var tx = Transaction.builder().body(TransactionBody.builder().inputs(new ArrayList<>())
                .outputs(new ArrayList<>(List.of(new TransactionOutput(address, Value.builder().coin(BigInteger.valueOf(22_000_000)).build()),
                        new TransactionOutput(address, Value.builder().coin(BigInteger.valueOf(5_000_000)).build()))))
                .fee(BigInteger.valueOf(200_000)).build()).witnessSet(TransactionWitnessSet.builder().build()).build();
        var protection = new SponsorFeeProtection(1, 1);
        protection.capture(null, tx);
        tx.getBody().getOutputs().removeLast();
        assertThrows(IllegalStateException.class, () -> protection.balance(null, tx));
    }
}
