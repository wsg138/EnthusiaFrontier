package net.enthusia.frontier.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreBoundaryPolicyTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000001";

    @Test
    void zeroRadiusManagesEveryChunkIncludingOrigin() {
        CoreBoundaryPolicy policy = new CoreBoundaryPolicy(0);
        assertTrue(policy.isManaged(new ChunkKey(WORLD, 0, 0)));
        assertTrue(policy.isManaged(new ChunkKey(WORLD, -100, 100)));
    }

    @Test
    void positiveRadiusKeepsIntersectingBoundaryChunksPermanent() {
        CoreBoundaryPolicy policy = new CoreBoundaryPolicy(100_000);
        assertFalse(policy.isManaged(new ChunkKey(WORLD, 0, 0)));

        // +6250 covers blocks 100000..100015, so it intersects the permanent core.
        assertFalse(policy.isManaged(new ChunkKey(WORLD, 6_250, 0)));
        assertTrue(policy.isManaged(new ChunkKey(WORLD, 6_251, 0)));

        // -6250 covers -100000..-99985 and intersects the core. -6251 covers
        // -100016..-100001 and is completely outside, so it is managed.
        assertFalse(policy.isManaged(new ChunkKey(WORLD, -6_250, 0)));
        assertTrue(policy.isManaged(new ChunkKey(WORLD, -6_251, 0)));
    }

    @Test
    void leavingCoreOnEitherAxisIsManaged() {
        CoreBoundaryPolicy policy = new CoreBoundaryPolicy(100_000);
        assertTrue(policy.isManaged(new ChunkKey(WORLD, 0, 6_251)));
        assertTrue(policy.isManaged(new ChunkKey(WORLD, 6_251, 0)));
    }
}
