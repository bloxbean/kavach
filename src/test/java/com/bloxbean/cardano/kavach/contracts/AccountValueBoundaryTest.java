package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Complete asset-anchor tests for numeric bounds; the native map implementation must not wrap or ignore assets. */
class AccountValueBoundaryTest {
    private final AccountFixtures f = new AccountFixtures();
    AccountValueBoundaryTest() throws Exception {}
    private Value tokens(long ada, BigInteger quantity) {
        return Value.lovelace(BigInteger.valueOf(ada)).merge(Value.singleton(new PolicyId(new byte[28]), new TokenName(new byte[]{1}), quantity));
    }
    private PlutusData tokenContext(BigInteger input, BigInteger change) throws Exception {
        var intent = f.spend(0); var ctx = f.context("asset", intent, f.authorization(intent)).buildPlutusData();
        ctx = AccountAdversarialTest.field(ctx, tokens(10000000, input).toPlutusData(), 0, 0, 0, 1, 1);
        return AccountAdversarialTest.field(ctx, tokens(8000000, change).toPlutusData(), 0, 2, 1, 1);
    }
    @Test void completeNativeMapPreservesSmallAndMaximumPartialQuantities() throws Exception {
        for (var quantity : new BigInteger[]{BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE)})
            assertInstanceOf(EvalResult.Success.class, f.evaluate("asset", tokenContext(quantity, quantity)));
    }
    @Test void partialQuantityOverflowLossAndCreationReject() throws Exception {
        var max = BigInteger.valueOf(Long.MAX_VALUE);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", tokenContext(max.add(BigInteger.ONE), max.add(BigInteger.ONE))));
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", tokenContext(max, max.subtract(BigInteger.ONE))));
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", tokenContext(BigInteger.ONE, BigInteger.TWO)));
    }
    @ParameterizedTest @ValueSource(longs={0, 1, 200000, 5000000})
    void exactPermittedFeeDebitPassesAndOneMoreLovelaceRejects(long fee) throws Exception {
        var intent = f.spend(fee);
        var ctx = f.context("asset", intent, f.authorization(intent)).fee(BigInteger.valueOf(fee)).buildPlutusData();
        ctx = AccountAdversarialTest.field(ctx, Value.lovelace(BigInteger.valueOf(8000000 - fee)).toPlutusData(), 0, 2, 1, 1);
        assertInstanceOf(EvalResult.Success.class, f.evaluate("asset", ctx));
        var over = AccountAdversarialTest.field(ctx, Value.lovelace(BigInteger.valueOf(7999999 - fee)).toPlutusData(), 0, 2, 1, 1);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", over));
    }
    @Test void signedFeeCannotExceedProtocolCeilingOrBeNegative() throws Exception {
        for (long fee : new long[]{-1, 5000001}) {
            var intent = f.spend(fee);
            assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", f.context("asset", intent, f.authorization(intent))));
        }
    }    @ParameterizedTest @ValueSource(strings={"aggregate-overflow", "asset-union"})
    void aggregateBoundsRejectEvenWhenEachInputAndOutputFits(String attack) throws Exception {
        Value a = tokens(10000000, BigInteger.valueOf(Long.MAX_VALUE));
        Value b = tokens(10000000, BigInteger.ONE);
        if (attack.equals("asset-union")) {
            a = Value.lovelace(BigInteger.valueOf(10000000)); b = a;
            for (int i = 0; i < 12; i++) {
                byte[] policy = new byte[28]; policy[0] = (byte)(12 - i);
                var token = Value.singleton(new PolicyId(policy), TokenName.EMPTY, BigInteger.ONE);
                if (i < 6) a = a.merge(token); else b = b.merge(token);
            }
        }
        var base = f.spend(0); var second = AccountFixtures.ref(31); var original = (Spend)base.action();
        var intent = new IntentEnvelope(base.protocolTag(), base.domain(), base.validity(),
                new Spend(AccountFixtures.list(f.assetRef, second), original.recipients(), BigInteger.ZERO));
        var ctx = f.context("asset", intent, f.authorization(intent)).buildPlutusData();
        ctx = AccountAdversarialTest.field(ctx, new PlutusData.ListData(List.of(
                new TxInInfo(f.assetRef, new TxOut(f.accountAddress, a, new OutputDatum.NoOutputDatum(), Optional.empty())).toPlutusData(),
                new TxInInfo(second, new TxOut(f.accountAddress, b, new OutputDatum.NoOutputDatum(), Optional.empty())).toPlutusData())), 0, 0);
        a = a.merge(Value.lovelace(BigInteger.valueOf(-1000000))); b = b.merge(Value.lovelace(BigInteger.valueOf(-1000000)));
        ctx = AccountAdversarialTest.field(ctx, new PlutusData.ListData(List.of(AccountFixtures.output(f.sink, 2000000).toPlutusData(),
                new TxOut(f.accountAddress, a, new OutputDatum.NoOutputDatum(), Optional.empty()).toPlutusData(),
                new TxOut(f.accountAddress, b, new OutputDatum.NoOutputDatum(), Optional.empty()).toPlutusData())), 0, 2);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", ctx), attack);
    }
    @ParameterizedTest @ValueSource(ints={0, 7, 8, 15})
    void signedRecipientMaskCoversBothBytesAndHighestOutputIndex(int index) throws Exception {
        var base = f.spend(0); var spend = (Spend)base.action(); var recipient = spend.recipients().head();
        var intent = new IntentEnvelope(base.protocolTag(), base.domain(), base.validity(), new Spend(spend.accountInputs(),
                AccountFixtures.list(new Recipient(BigInteger.valueOf(index), recipient.address(), recipient.value())), BigInteger.ZERO));
        var ctx = f.context("asset", intent, f.authorization(intent)).buildPlutusData();
        var outputs = new ArrayList<PlutusData>(); int changeIndex = index == 0 ? 1 : 0;
        for (int i = 0; i < 16; i++) outputs.add(AccountFixtures.output(i == changeIndex ? f.accountAddress : f.sink, i == changeIndex ? 8000000 : 2000000).toPlutusData());
        ctx = AccountAdversarialTest.field(ctx, new PlutusData.ListData(outputs), 0, 2);
        assertInstanceOf(EvalResult.Success.class, f.evaluate("asset", ctx), "recipient index " + index);
    }

}
