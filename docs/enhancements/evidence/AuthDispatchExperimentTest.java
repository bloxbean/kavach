package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class AuthDispatchExperimentTest {
    @Test void measureTransfers() throws Exception {
        for (boolean maximum : new boolean[]{false, true}) for (int mode : new int[]{1, 2, 3}) {
            var f = new AccountFixtures(maximum, mode);
            var intent = f.spend(0);
            var digest = AccountCodec.intentDigest(intent, null);
            var ids = maximum ? new int[]{0,1,2,3,4,5,6,7} : mode == 3 ? new int[]{0,1} : new int[]{0};
            var signatures = new ArrayList<Signature>();
            for (int id : ids) {
                int method = mode == 3 ? (id == 1 ? 2 : 1) : mode;
                signatures.add(BrowserModuleTest.proofs(f, method, digest, id).head());
            }
            var auth = new ModuleRedeemer(BigInteger.ONE, intent,
                    Optional.of(new Proof(BigInteger.valueOf(mode), AccountFixtures.list(signatures.toArray(Signature[]::new)))),
                    JulcList.empty(), JulcList.empty());
            long cpu=0, memory=0;
            for (String role : List.of("asset", "core", "module")) {
                var context = f.context(role, intent, auth);
                for (int id : ids) if (mode == 1 || mode == 3 && id != 1)
                    context.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(id))));
                var success = assertInstanceOf(EvalResult.Success.class, f.evaluate(role, context));
                cpu += success.consumed().cpuSteps(); memory += success.consumed().memoryUnits();
            }
            System.out.println("EXPERIMENT mode="+mode+" maximum="+maximum+" moduleCborBytes="+f.moduleScript.getCborHex().length()/2+" cpu="+cpu+" memory="+memory);
        }
    }
}
