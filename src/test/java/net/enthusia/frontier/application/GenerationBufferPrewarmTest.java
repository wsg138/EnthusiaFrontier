package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import net.enthusia.frontier.domain.ChunkKey;
import org.junit.jupiter.api.Test;

class GenerationBufferPrewarmTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000019";

    @Test
    void repeatedPrewarmForSameCenterDoesNotResubmitLeadingStrip() {
        Harness harness = new Harness();
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        coordinator.prewarm("player", WORLD, 0, 0, 1, 0, 1);
        assertEquals(3, harness.shield.metrics().queued());
        assertEquals(0, harness.shield.metrics().deduplicated());
        assertEquals(0, coordinator.pendingBuffers());

        coordinator.prewarm("player", WORLD, 0, 0, 1, 0, 1);
        assertEquals(3, harness.shield.metrics().queued());
        assertEquals(0, harness.shield.metrics().deduplicated());
        assertEquals(0, coordinator.pendingBuffers());
    }

    @Test
    void diagonalPredictionQueuesOnlyTwoExposedStrips() {
        Harness harness = new Harness();
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        coordinator.prewarm("player", WORLD, 0, 0, 1, 1, 1);
        assertEquals(5, harness.shield.metrics().queued());
    }

    @Test
    void distantPredictionIsRejected() {
        Harness harness = new Harness();
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.prewarm("player", WORLD, 0, 0, 2, 0, 1));
    }

    @Test
    void differentBlockingCenterDoesNotAppendBehindExistingPendingSquare() {
        Harness harness = new Harness();
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("player", WORLD, 0, 0, 1));
        assertEquals(9, harness.shield.metrics().queued());

        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("player", WORLD, 10, 0, 1));
        assertEquals(9, harness.shield.metrics().queued());
        assertEquals(1, coordinator.pendingBuffers());
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
