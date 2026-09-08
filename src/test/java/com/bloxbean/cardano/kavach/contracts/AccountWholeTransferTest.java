package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Large-value escape path executes through the real immutable anchor, core and spend module. */
class AccountWholeTransferTest {
    @Test void wholeTransferPreserves140TokensAndAllowsOnlySponsorAdaTopup() throws Exception {
        var f = new AccountFixtures();
        Value value = Value.lovelace(BigInteger.valueOf(20000000));
        for (int i = 139; i >= 0; i--) {
            byte[] name = new byte[32]; name[0] = (byte)i;
            value = value.merge(Value.singleton(new PolicyId(new byte[28]), new TokenName(name), new BigInteger("18446744073709551615")));
        }
        var initial = f.spend(0);
        var intent = new IntentEnvelope(initial.protocolTag(), initial.domain(), initial.validity(), new TransferWholeUtxo(f.assetRef,
                BigInteger.ZERO, f.sink, WireFormat.ledgerValueDigest(value.toPlutusData())));
        var auth = f.authorization(intent);
        Value toppedUp = Value.lovelace(BigInteger.valueOf(1000000)).merge(value);
        var incoming = new TxInInfo(f.assetRef, new TxOut(f.accountAddress, value, new OutputDatum.NoOutputDatum(), Optional.empty()));
        var recipient = new TxOut(f.sink, toppedUp, new OutputDatum.NoOutputDatum(), Optional.empty());
        for (var role : List.of("asset", "core", "module")) {
            var context = f.context(role, intent, auth).buildPlutusData();
            context = AccountAdversarialTest.field(context, new PlutusData.ListData(List.of(incoming.toPlutusData())), 0, 0);
            context = AccountAdversarialTest.field(context, new PlutusData.ListData(List.of(recipient.toPlutusData())), 0, 2);
            var evaluated = f.evaluate(role, context);
            var result = assertInstanceOf(EvalResult.Success.class, evaluated, role + ": " + evaluated);
            System.out.println("Phase 1 whole " + role + " budget " + result.consumed());
            if (role.equals("asset")) {
                var lost = AccountAdversarialTest.field(context, Value.lovelace(BigInteger.valueOf(21000000)).toPlutusData(), 0, 2, 0, 1);
                assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, lost));
                var alteredInput = AccountAdversarialTest.field(context, Value.lovelace(BigInteger.valueOf(20000000)).toPlutusData(), 0, 0, 0, 1, 1);
                assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, alteredInput));
            }
        }
    }
}
