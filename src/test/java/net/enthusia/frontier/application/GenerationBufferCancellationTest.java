package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.GlobalGenerationLimits;
import org.junit.jupiter.api.Test;

class GenerationBufferCancellationTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000019";

    @Test
    void replacingOwnerTargetRequeuesChunkStillNeededByDeduplicatedRequester() {
        AtomicLong now = new AtomicLong();
        FakeReadiness readiness = new FakeReadiness();
        GenerationShieldService shield = new GenerationShieldService(
                64,
                ignored -> true,
                readiness,
                key -> new CompletableFuture<>(),
                () -> true,
                now::get,
                ignored -> { });
        shield.setLimits(new GlobalGenerationLimits(1000.0, 4));
        GenerationBufferCoordinator coordinator = new GenerationBufferCoordinator(shield, readiness);

        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("owner", WORLD, 0, 0, 0));
        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("waiter", WORLD, 0, 0, 0));
        assertEquals(1, shield.metrics().queued());

        // Moving the original owner releases chunk 0,0 and queues the owner's new target.
        assertEquals(GenerationBufferStatus.PENDING, coordinator.prepare("owner", WORLD, 1, 0, 0));
        assertEquals(1, shield.metrics().queued());

        // The waiter's previously deduplicated 0,0 request must be resubmitted, not orphaned.
        coordinator.refresh(16);
        assertEquals(2, shield.metrics().queued());
    }

    private static final class FakeReadiness implements GenerationReadinessPort {
        private final Set<ChunkKey> ready = new HashSet<>();

        @Override
        public boolean isReady(ChunkKey key) {
            return ready.contains(key);
        }

        @Override
        public boolean observeReady(ChunkKey key) {
            return ready.add(key);
        }

        @Override
        public CompletableFuture<Void> markReady(ChunkKey key) {
            ready.add(key);
            return CompletableFuture.completedFuture(null);
        }
    }
}
