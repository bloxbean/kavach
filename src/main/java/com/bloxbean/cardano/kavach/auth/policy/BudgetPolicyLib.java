package com.bloxbean.cardano.kavach.auth.policy;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Configuration;

/** Decodes the core budget envelope before the mixed module validates its authorization payload. */
@OnchainLibrary
public class BudgetPolicyLib {
    /** Rejects malformed or unusable core budget configuration before accepting new-key possession. */
    public static PolicyConfig authorization(PlutusData data) {
        var configuration = (Configuration)(Object)data;
        if (!PeriodicBudgetLib.configuration(configuration)) return (PolicyConfig)(Object)Builtins.error();
        return (PolicyConfig)(Object)configuration.authorization();
    }
}
