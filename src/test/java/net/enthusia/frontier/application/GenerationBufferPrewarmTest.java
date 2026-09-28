package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import net.enthusia.frontier.domain.ChunkKey;
import org.junit.jupiter.api.Test;

class GenerationBufferPrewarmTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000019";

    @Test
    void repeatedPrewarmForSameCenterDoesNotResubmitSquare() {
        Harness harness = new Harness();
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        coordinator.prewarm("player", WORLD, 0, 0, 1);
        assertEquals(9, harness.shield.metrics().queued());
        assertEquals(0, harness.shield.metrics().deduplicated());
        assertEquals(0, coordinator.pendingBuffers());

        coordinator.prewarm("player", WORLD, 0, 0, 1);
        assertEquals(9, harness.shield.metrics().queued());
        assertEquals(0, harness.shield.metrics().deduplicated());
        assertEquals(0, coordinator.pendingBuffers());
    }

    private static final class Harness {
        private final FakeReadiness readiness = new FakeReadiness();
        private final GenerationShieldService shield = new GenerationShieldService(
                64,
                ignored -> true,
                readiness,
                ignored -> CompletableFuture.completedFuture(null),
                () -> true,
                System::nanoTime,
                ignored -> { });
    }

    private static final class FakeReadiness implements GenerationReadinessPort {
        @Override
        public boolean isReady(ChunkKey key) {
            return false;
        }

        @Override
        public boolean observeReady(ChunkKey key) {
            return false;
        }

        @Override
        public CompletableFuture<Void> markReady(ChunkKey key) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
