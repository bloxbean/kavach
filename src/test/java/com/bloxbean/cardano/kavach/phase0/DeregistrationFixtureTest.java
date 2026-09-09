package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DeregistrationFixtureTest {
    @ParameterizedTest
    @ValueSource(strings = {"authorized", "no-authority", "registration", "rewarding", "wrong-redeemer"})
    void fixtureHasNoUnconditionalCertificateOrRewardBranch(String branch) {
        byte[] authority = new byte[28];
        var script = JulcScriptLoader.load(DeregistrationFixture.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(authority)));
        var credential = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        TxCert certificate = branch.equals("registration") ? new TxCert.RegStaking(credential, Optional.empty())
                : new TxCert.UnRegStaking(credential, Optional.of(BigInteger.valueOf(2_000_000)));
        var context = branch.equals("rewarding") ? ScriptContextTestBuilder.rewarding(credential)
                : ScriptContextTestBuilder.certifying(BigInteger.ZERO, certificate);
        if (!branch.equals("no-authority")) context.signer(authority);
        context.redeemer(PlutusData.integer(branch.equals("wrong-redeemer") ? 1 : 0));
        var result = JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(context.buildPlutusData()),
                new ExBudget(100_000_000, 1_000_000), EvalOptions.DEFAULT);
        if (branch.equals("authorized")) assertInstanceOf(EvalResult.Success.class, result);
        else assertInstanceOf(EvalResult.Failure.class, result);
    }
}
