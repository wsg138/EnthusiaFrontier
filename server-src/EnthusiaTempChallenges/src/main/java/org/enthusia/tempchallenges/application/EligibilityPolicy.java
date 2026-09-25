package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.Eligibility;
import org.enthusia.tempchallenges.domain.PlayerContext;

public final class EligibilityPolicy {
    private final boolean excludeOperators;
    private final boolean allowAdventure;

    public EligibilityPolicy(boolean excludeOperators, boolean allowAdventure) {
        this.excludeOperators = excludeOperators;
        this.allowAdventure = allowAdventure;
    }

    public Eligibility evaluate(PlayerContext context) {
        if (context.recentAdminMutation()) return Eligibility.deny("recent-admin-mutation");
        if (context.excludedPermission()) return Eligibility.deny("excluded-permission");
        if (excludeOperators && context.operator()) return Eligibility.deny("operator-excluded");
        if (context.mode() == PlayerContext.Mode.CREATIVE) return Eligibility.deny("creative-excluded");
        if (context.mode() == PlayerContext.Mode.SPECTATOR) return Eligibility.deny("spectator-excluded");
        if (context.mode() == PlayerContext.Mode.ADVENTURE && !allowAdventure) return Eligibility.deny("adventure-excluded");
        return Eligibility.allow();
    }
}
