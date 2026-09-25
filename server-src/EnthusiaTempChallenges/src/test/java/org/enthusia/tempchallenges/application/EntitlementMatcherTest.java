package org.enthusia.tempchallenges.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementMatcherTest {
    @Test void wildcardAndOpStyleNodesDoNotCountAsExplicitOwnership() {
        String required = "enthusia.frontier.first.diamonds";
        assertTrue(EntitlementMatcher.ownsExplicit(List.of(required), required));
        assertFalse(EntitlementMatcher.ownsExplicit(List.of("*"), required));
        assertFalse(EntitlementMatcher.ownsExplicit(List.of("enthusia.frontier.first.*"), required));
        assertFalse(EntitlementMatcher.ownsExplicit(List.of("enthusia.frontier.first.nether"), required));
    }
}
