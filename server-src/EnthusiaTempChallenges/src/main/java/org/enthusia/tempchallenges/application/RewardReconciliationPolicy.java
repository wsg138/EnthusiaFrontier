package org.enthusia.tempchallenges.application;

public final class RewardReconciliationPolicy {
    private RewardReconciliationPolicy() { }

    public enum Scope {
        NONE,
        PRESENTATION_AND_PORTABLE,
        FULL_LOCAL_WINNER
    }

    public static Scope decide(boolean localWinner, boolean explicitPortableEntitlement) {
        if (localWinner) return Scope.FULL_LOCAL_WINNER;
        if (explicitPortableEntitlement) return Scope.PRESENTATION_AND_PORTABLE;
        return Scope.NONE;
    }
}
