package org.enthusia.tempchallenges.paper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FloodgateIdentityResolverTest {
    @Test void linkedCorrectUuidWinsOverFloodgateRuntimeUuid() {
        UUID runtime = UUID.randomUUID();
        UUID linked = UUID.randomUUID();
        assertEquals(linked, FloodgateIdentityResolver.canonicalUuid(runtime, linked));
        assertEquals(runtime, FloodgateIdentityResolver.canonicalUuid(runtime, null));
    }
}
