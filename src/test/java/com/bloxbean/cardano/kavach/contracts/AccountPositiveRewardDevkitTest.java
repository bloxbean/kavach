package com.bloxbean.cardano.kavach.contracts;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Full account creation and spend with actual positive core/module rewards at a shared sink. */
@Tag("phase1RewardCredit")
@Timeout(6000)
class AccountPositiveRewardDevkitTest {
    @Test void fullAccountSpendClearsBothRealRewardBalances() throws Exception {
        new AccountDevkitTest().createFundAndSpend(true);
    }
}
