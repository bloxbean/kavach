package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic generated allocations test net conservation across all composed transfer scripts.
 */
class AccountConservationPropertyTest {
    private static Value value(long ada, long[] quantities, byte[][] names) {
        var value = Value.lovelace(BigInteger.valueOf(ada));
        for (int i = quantities.length - 1; i >= 0; i--) {
            byte[] policy = new byte[28];
            policy[0] = (byte) (i + 1);
            value = value.merge(Value.singleton(new PolicyId(policy), new TokenName(names[i]), BigInteger.valueOf(quantities[i])));
        }
        return value;
    }

    @Test
    void variedNativeAllocationsPreserveAllAssetsAndOneUnitDiversionsFail() throws Exception {
        var f = new AccountFixtures();
        var random = new Random(113001L);
        for (int sample = 0; sample < 64; sample++) {
            long[] a = new long[3], b = new long[3], paid = new long[3], changeA = new long[3], changeB = new long[3];
            byte[][] names = new byte[3][];
            var signed = new ArrayList<Asset>();
            signed.add(new Asset(new byte[0], new byte[0], BigInteger.valueOf(2000000)));
            for (int i = 0; i < 3; i++) {
                names[i] = new byte[random.nextInt(33)];
                random.nextBytes(names[i]);
                a[i] = 10 + random.nextLong(1L << 60);
                b[i] = 10 + random.nextLong(1L << 60);
                paid[i] = 1 + random.nextLong(a[i] + b[i] - 3);
                changeA[i] = 1 + random.nextLong(a[i] + b[i] - paid[i] - 1);
                changeB[i] = a[i] + b[i] - paid[i] - changeA[i];
                byte[] policy = new byte[28];
                policy[0] = (byte) (i + 1);
                signed.add(new Asset(policy, names[i], BigInteger.valueOf(paid[i])));
            }
            var ordinary = f.spend(0);
            var second = AccountFixtures.ref(31);
            var intent = new IntentEnvelope(ordinary.protocolTag(), ordinary.domain(), ordinary.validity(),
                    new Spend(AccountFixtures.list(f.assetRef, second), AccountFixtures.list(new Recipient(BigInteger.ZERO, f.sink,
                            AccountFixtures.list(signed.toArray(Asset[]::new)))), BigInteger.ZERO));
            var auth = f.authorization(intent);
            var inputA = new TxInInfo(f.assetRef, new TxOut(f.accountAddress, value(20000000, a, names), new OutputDatum.NoOutputDatum(), Optional.empty()));
            var inputB = new TxInInfo(second, new TxOut(f.accountAddress, value(20000000, b, names), new OutputDatum.NoOutputDatum(), Optional.empty()));
            var sponsor = new TxInInfo(AccountFixtures.ref(40), AccountFixtures.output(f.sink, 2000000));
            List<PlutusData> outputs = List.of(new TxOut(f.sink, value(2000000, paid, names), new OutputDatum.NoOutputDatum(), Optional.empty()).toPlutusData(),
                    new TxOut(f.accountAddress, value(19000000, changeA, names), new OutputDatum.NoOutputDatum(), Optional.empty()).toPlutusData(),
                    new TxOut(f.accountAddress, value(19000000, changeB, names), new OutputDatum.NoOutputDatum(), Optional.empty()).toPlutusData(),
                    AccountFixtures.output(f.sink, 1800000).toPlutusData());
            for (var role : List.of("core", "module", "asset")) {
                var ctx = f.context(role, intent, auth).redeemerEntry(new ScriptPurpose.Spending(second),
                        AccountCodec.data(new AssetRedeemer(BigInteger.ONE, AccountCodec.intentDigest(intent, null)))).buildPlutusData();
                ctx = AccountAdversarialTest.field(ctx, new PlutusData.ListData(List.of(inputA.toPlutusData(), inputB.toPlutusData(), sponsor.toPlutusData())), 0, 0);
                ctx = AccountAdversarialTest.field(ctx, new PlutusData.ListData(outputs), 0, 2);
                assertInstanceOf(EvalResult.Success.class, f.evaluate(role, ctx), role + " allocation sample " + sample);
                if (role.equals("asset")) {
                    changeB[0]--; // Never touch the signed recipient: test complete account-change accounting.
                    var changed = AccountAdversarialTest.field(ctx, value(19000000, changeB, names).toPlutusData(), 0, 2, 2, 1);
                    assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, changed), "one-unit loss sample " + sample);
                    changeB[0]++;
                }
            }
        }
    }
}
