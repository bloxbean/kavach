package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidityWindowProbeTest {
    private final byte[] authority = new byte[28];

    private Interval interval(long lower, long upper, boolean upperInclusive) {
        return new Interval(new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(lower)), true),
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(upper)), upperInclusive));
    }

    private EvalResult evaluate(Interval interval, long lower, long upper, long deadline, boolean signer) {
        var ctx = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28])))
                .redeemer(PlutusData.constr(0, PlutusData.integer(lower), PlutusData.integer(upper), PlutusData.integer(deadline))).validRange(interval);
        if (signer) ctx.signer(authority);
        var script = JulcScriptLoader.load(ValidityWindowProbe.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(authority)));
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(ctx.buildPlutusData()), new ExBudget(100_000_000, 1_000_000), EvalOptions.DEFAULT);
    }

    @Test
    void exactHalfOpenContainmentAndDeadlineSucceed() {
        assertInstanceOf(EvalResult.Success.class, evaluate(interval(1000, 2000, false), 1000, 2000, 1000, true));
        assertInstanceOf(EvalResult.Success.class, evaluate(interval(1000, 1999, true), 1000, 2000, 1000, true));
        assertInstanceOf(EvalResult.Success.class, evaluate(interval(0, 300000, false), 0, 300000, 0, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lower-outside", "upper-outside", "closed-upper", "infinite-lower", "infinite-upper", "empty", "zero-width", "wide", "negative", "deadline", "no-authority"})
    void rejectsUnsafeIntervals(String mutation) {
        Interval range = interval(1000, 2000, false);
        long lower = 1000, upper = 2000, deadline = 1000;
        switch (mutation) {
            case "lower-outside" -> range = interval(999, 2000, false);
            case "upper-outside" -> range = interval(1000, 2001, false);
            case "closed-upper" -> range = interval(1000, 2000, true);
            case "infinite-lower" -> range = Interval.before(BigInteger.valueOf(1999));
            case "infinite-upper" -> range = Interval.after(BigInteger.valueOf(1000));
            case "empty" -> range = interval(1000, 1000, false);
            case "zero-width" -> upper = lower;
            case "wide" -> upper = 301001;
            case "negative" -> lower = -1;
            case "deadline" -> deadline = 1001;
        }
        assertInstanceOf(EvalResult.Failure.class, evaluate(range, lower, upper, deadline, !mutation.equals("no-authority")));
    }
}
