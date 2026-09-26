package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.GlobalGenerationLimits;
import org.junit.jupiter.api.Test;

class GenerationBufferCoordinatorTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000009";

    @Test
    void repeatedChecksForSameCenterDoNotResubmitSquare() {
        Harness harness = new Harness(64);
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("player", WORLD, 0, 0, 1));
        GenerationShieldMetrics first = harness.shield.metrics();
        assertEquals(9, first.queued());
        assertEquals(0, first.deduplicated());

        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("player", WORLD, 0, 0, 1));
        GenerationShieldMetrics second = harness.shield.metrics();
        assertEquals(first.queued(), second.queued());
        assertEquals(first.deduplicated(), second.deduplicated());
        assertEquals(1, coordinator.pendingBuffers());
    }

    @Test
    void readySquareBypassesGenerationQueue() {
        Harness harness = new Harness(64);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                harness.readiness.ready.add(key(x, z));
            }
        }
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);

        assertEquals(GenerationBufferStatus.READY, coordinator.prepare("player", WORLD, 0, 0, 1));
        assertEquals(0, harness.shield.metrics().queued());
        assertEquals(0, coordinator.pendingBuffers());
    }

    @Test
    void refreshObservesDurablyReadyChunksAndClearsPendingBuffer() {
        Harness harness = new Harness(64);
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);
        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("player", WORLD, 4, 4, 0));

        harness.readiness.ready.add(key(4, 4));
        coordinator.refresh(4);

        assertEquals(0, coordinator.pendingBuffers());
        assertEquals(GenerationBufferStatus.READY, coordinator.prepare("player", WORLD, 4, 4, 0));
    }

    @Test
    void unhealthyShieldFailsClosedAndRequesterCanBeRemoved() {
        Harness harness = new Harness(64);
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);
        coordinator.prepare("player", WORLD, 2, 2, 0);
        assertEquals(1, coordinator.pendingBuffers());
        coordinator.removeRequester("player");
        assertEquals(0, coordinator.pendingBuffers());

        harness.healthy.set(false);
        assertEquals(GenerationBufferStatus.FAIL_CLOSED, coordinator.prepare("player", WORLD, 3, 3, 0));
    }

    @Test
    void capacityRejectedChunksAreRetriedDuringRefresh() {
        Harness harness = new Harness(1);
        harness.shield.setLimits(new GlobalGenerationLimits(1000.0, 1));
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(harness.shield, harness.readiness);
        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("player", WORLD, 0, 0, 1));
        assertEquals(1, harness.shield.metrics().queued());

        for (int iteration = 0; iteration < 20 && coordinator.pendingBuffers() > 0; iteration++) {
            harness.shield.pump();
            harness.advanceMillis(2);
            coordinator.refresh(64);
        }

        assertEquals(0, coordinator.pendingBuffers());
        assertEquals(9, harness.generation.started.size());
        assertTrue(harness.generation.started.contains(key(1, 1)));
    }

    private static ChunkKey key(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static final class Harness {
        private final AtomicLong now = new AtomicLong();
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private final FakeReadiness readiness = new FakeReadiness();
        private final FakeGeneration generation = new FakeGeneration(readiness);
        private final GenerationShieldService shield;

        private Harness(int capacity) {
            shield = new GenerationShieldService(
                    capacity,
                    ignored -> true,
                    readiness,
                    generation,
                    healthy::get,
                    now::get,
                    ignored -> { });
            shield.setLimits(new GlobalGenerationLimits(1000.0, 4));
        }

        private void advanceMillis(long millis) {
            now.addAndGet(millis * 1_000_000L);
        }
    }

    private static final class FakeReadiness implements GenerationReadinessPort {
        private final Set<ChunkKey> ready = new HashSet<>();

        @Override
        public boolean isReady(ChunkKey key) {
            return ready.contains(key);
        }

        @Override
        public CompletableFuture<Void> markReady(ChunkKey key) {
            ready.add(key);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FakeGeneration implements ChunkGenerationPort {
        private final FakeReadiness readiness;
        private final List<ChunkKey> started = new ArrayList<>();

        private FakeGeneration(FakeReadiness readiness) {
            this.readiness = readiness;
        }

        @Override
        public CompletableFuture<Void> generate(ChunkKey key) {
            started.add(key);
            readiness.ready.add(key);
            return CompletableFuture.completedFuture(null);
        }
    }
}
