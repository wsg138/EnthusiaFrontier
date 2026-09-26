package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.GlobalGenerationLimits;
import org.junit.jupiter.api.Test;

class GenerationShieldServiceTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000001";

    @Test
    void fortyRequestersShareOneGlobalRateBudget() {
        Harness harness = new Harness(64, true);
        harness.service.setLimits(new GlobalGenerationLimits(4.0, 40));
        for (int index = 0; index < 40; index++) {
            assertEquals(GenerationAdmission.QUEUED,
                    harness.service.request("player-" + index, key(index, 0)));
        }

        assertEquals(1, harness.service.pump());
        harness.advanceMillis(250);
        assertEquals(1, harness.service.pump());
        harness.advanceMillis(250);
        assertEquals(1, harness.service.pump());
        harness.advanceMillis(250);
        assertEquals(1, harness.service.pump());

        assertEquals(4, harness.generation.started.size());
        assertEquals(36, harness.service.metrics().queued());
    }

    @Test
    void concurrencyBudgetIsGlobal() {
        Harness harness = new Harness(16, true);
        harness.service.setLimits(new GlobalGenerationLimits(1000.0, 2));
        for (int index = 0; index < 5; index++) {
            harness.service.request("player-" + index, key(index, 1));
        }

        assertEquals(1, harness.service.pump());
        harness.advanceMillis(1);
        assertEquals(1, harness.service.pump());
        harness.advanceMillis(100);
        assertEquals(0, harness.service.pump());
        assertEquals(2, harness.service.metrics().inFlight());

        harness.generation.complete(harness.generation.started.get(0));
        assertEquals(1, harness.service.pump());
        assertEquals(2, harness.service.metrics().inFlight());
    }

    @Test
    void duplicateChunkRequestsCollapseAcrossPlayers() {
        Harness harness = new Harness(64, true);
        ChunkKey shared = key(8, 8);
        assertEquals(GenerationAdmission.QUEUED, harness.service.request("player-0", shared));
        for (int index = 1; index < 40; index++) {
            assertEquals(GenerationAdmission.DEDUPLICATED,
                    harness.service.request("player-" + index, shared));
        }
        assertEquals(1, harness.service.metrics().queued());
        assertEquals(39, harness.service.metrics().deduplicated());
    }

    @Test
    void boundedQueueRejectsExcessWithoutLosingExistingRequests() {
        Harness harness = new Harness(2, true);
        assertEquals(GenerationAdmission.QUEUED, harness.service.request("a", key(1, 2)));
        assertEquals(GenerationAdmission.QUEUED, harness.service.request("b", key(2, 2)));
        assertEquals(GenerationAdmission.REJECTED_CAPACITY, harness.service.request("c", key(3, 2)));
        assertEquals(2, harness.service.metrics().queued());
        assertEquals(1, harness.service.metrics().rejected());
    }

    @Test
    void schedulingRotatesBetweenRequesters() {
        Harness harness = new Harness(16, true);
        harness.service.setLimits(new GlobalGenerationLimits(1000.0, 1));
        harness.service.request("a", key(1, 3));
        harness.service.request("a", key(2, 3));
        harness.service.request("a", key(3, 3));
        harness.service.request("b", key(4, 3));
        harness.service.request("c", key(5, 3));

        harness.service.pump();
        harness.generation.complete(key(1, 3));
        harness.advanceMillis(1);
        harness.service.pump();
        harness.generation.complete(key(4, 3));
        harness.advanceMillis(1);
        harness.service.pump();

        assertEquals(List.of(key(1, 3), key(4, 3), key(5, 3)),
                harness.generation.started.subList(0, 3));
    }

    @Test
    void criticalLimitsAdmitZeroNewGeneration() {
        Harness harness = new Harness(8, true);
        harness.service.setLimits(new GlobalGenerationLimits(0.0, 0));
        harness.service.request("a", key(1, 4));
        harness.advanceMillis(60_000);
        assertEquals(0, harness.service.pump());
        assertTrue(harness.generation.started.isEmpty());
        assertEquals(1, harness.service.metrics().queued());
    }

    @Test
    void coreBypassesQueueAndUnhealthyStateFailsClosed() {
        Harness core = new Harness(8, false);
        assertEquals(GenerationAdmission.CORE_BYPASS, core.service.request("a", key(1, 5)));
        assertEquals(0, core.service.metrics().queued());

        Harness unhealthy = new Harness(8, true);
        unhealthy.externalHealthy.set(false);
        assertEquals(GenerationAdmission.REJECTED_UNHEALTHY,
                unhealthy.service.request("a", key(1, 6)));
        assertFalse(unhealthy.service.metrics().healthy());
    }

    @Test
    void generationFailureLatchesShieldUnhealthy() {
        Harness harness = new Harness(8, true);
        harness.service.setLimits(new GlobalGenerationLimits(10.0, 1));
        ChunkKey failed = key(1, 7);
        harness.service.request("a", failed);
        harness.service.pump();
        harness.generation.fail(failed);

        assertFalse(harness.service.metrics().healthy());
        assertEquals(GenerationAdmission.REJECTED_UNHEALTHY,
                harness.service.request("b", key(2, 7)));
        assertFalse(harness.failures.isEmpty());
    }

    private static ChunkKey key(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static final class Harness {
        private final AtomicLong now = new AtomicLong();
        private final AtomicBoolean externalHealthy = new AtomicBoolean(true);
        private final FakeReadiness readiness = new FakeReadiness();
        private final FakeGeneration generation = new FakeGeneration();
        private final List<String> failures = new ArrayList<>();
        private final GenerationShieldService service;

        private Harness(int capacity, boolean managed) {
            service = new GenerationShieldService(
                    capacity,
                    ignored -> managed,
                    readiness,
                    generation,
                    externalHealthy::get,
                    now::get,
                    failures::add);
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
        public void markReady(ChunkKey key) {
            ready.add(key);
        }
    }

    private static final class FakeGeneration implements ChunkGenerationPort {
        private final List<ChunkKey> started = new ArrayList<>();
        private final Map<ChunkKey, CompletableFuture<Void>> futures = new HashMap<>();

        @Override
        public CompletableFuture<Void> generate(ChunkKey key) {
            started.add(key);
            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.put(key, future);
            return future;
        }

        private void complete(ChunkKey key) {
            futures.get(key).complete(null);
        }

        private void fail(ChunkKey key) {
            futures.get(key).completeExceptionally(new IllegalStateException("test failure"));
        }
    }
}
