package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.PlayerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EligibilityPolicyTest {
    private final EligibilityPolicy policy = new EligibilityPolicy(true, false);

    @Test void normalSurvivalIsEligible() {
        assertTrue(policy.evaluate(new PlayerContext(PlayerContext.Mode.SURVIVAL, false, false, false)).eligible());
    }

    @Test void creativeSpectatorOperatorAndAdminMutationAreExcluded() {
        assertFalse(policy.evaluate(new PlayerContext(PlayerContext.Mode.CREATIVE, false, false, false)).eligible());
        assertFalse(policy.evaluate(new PlayerContext(PlayerContext.Mode.SPECTATOR, false, false, false)).eligible());
        assertFalse(policy.evaluate(new PlayerContext(PlayerContext.Mode.SURVIVAL, true, false, false)).eligible());
        assertFalse(policy.evaluate(new PlayerContext(PlayerContext.Mode.SURVIVAL, false, false, true)).eligible());
        assertFalse(policy.evaluate(new PlayerContext(PlayerContext.Mode.SURVIVAL, false, true, false)).eligible());
    }
}
