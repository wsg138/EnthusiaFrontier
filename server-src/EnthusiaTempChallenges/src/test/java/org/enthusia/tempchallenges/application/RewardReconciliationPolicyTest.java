package org.enthusia.tempchallenges.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RewardReconciliationPolicyTest {
    @Test
    void localWinnerGetsFullRecovery() {
        assertEquals(RewardReconciliationPolicy.Scope.FULL_LOCAL_WINNER,
                RewardReconciliationPolicy.decide(true, false));
    }

    @Test
    void portableEntitlementRestoresPresentationWithoutRegrantingLocalXp() {
        assertEquals(RewardReconciliationPolicy.Scope.PRESENTATION_AND_PORTABLE,
                RewardReconciliationPolicy.decide(false, true));
    }

    @Test
    void unrelatedPlayerGetsNothing() {
        assertEquals(RewardReconciliationPolicy.Scope.NONE,
                RewardReconciliationPolicy.decide(false, false));
    }
}
