package org.enthusia.tempchallenges.application;

import java.util.Collection;

public final class EntitlementMatcher {
    private EntitlementMatcher() { }

    public static boolean ownsExplicit(Collection<String> directPositiveGlobalPermissions, String requiredPermission) {
        return directPositiveGlobalPermissions.stream().anyMatch(node -> node.equalsIgnoreCase(requiredPermission));
    }
}
