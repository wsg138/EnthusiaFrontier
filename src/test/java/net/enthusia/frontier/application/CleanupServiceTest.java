package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.enthusia.frontier.config.CleanupSettings;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.enthusia.frontier.domain.RegionKey;
import org.junit.jupiter.api.Test;

class CleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Test
    void dryRunNeverTouchesStorage() throws Exception {
        Fixture fixture = new Fixture(settings(true), 20.0, true);
        try {
            CleanupResult result = fixture.service.process("world", fixture.candidate());
            assertEquals(CleanupResult.DRY_RUN, result);
            assertEquals(0, fixture.storage.clears);
        } finally {
            fixture.close();
        }
    }

    @Test
    void destructiveClearPersistsDeletedMarkerBeforeRegionCanBeReclaimed() throws Exception {
        Fixture fixture = new Fixture(settings(false), 20.0, true);
        try {
            assertEquals(CleanupResult.CLEARED, fixture.service.process("world", fixture.candidate()));
            assertTrue(await(fixture.journal::isIdle));
            assertEquals(1, fixture.storage.clears);
            assertTrue(fixture.repository.applied.stream().anyMatch(FrontierMutation.Deleted.class::isInstance));
            assertEquals(
                    RegionReclaimResult.RECLAIMED,
                    fixture.service.reclaim("world", RegionKey.fromChunk(fixture.candidate().key())));
        } finally {
            fixture.close();
        }
    }

    @Test
    void recentProtectionMsptPressureAndLatchAllFailClosed() throws Exception {
        Fixture protectedFixture = new Fixture(settings(false), 20.0, true);
        try {
            protectedFixture.tracking.recordActivity("world", WORLD_UUID, 10, 11, ActivityKind.BLOCK_PLACE);
            assertTrue(await(protectedFixture.journal::isIdle));
            assertEquals(CleanupResult.PROTECTED, protectedFixture.service.process("world", protectedFixture.candidate()));
            assertEquals(0, protectedFixture.storage.clears);
        } finally {
            protectedFixture.close();
        }

        Fixture pressured = new Fixture(settings(false), 40.0, true);
        try {
            assertEquals(CleanupResult.DEFERRED, pressured.service.process("world", pressured.candidate()));
        } finally {
            pressured.close();
        }

        Fixture latched = new Fixture(settings(false), 20.0, true);
        try {
            latched.latch.trip("planned");
            assertEquals(CleanupResult.LATCHED, latched.service.process("world", latched.candidate()));
        } finally {
            latched.close();
        }
    }

    private static CleanupSettings settings(boolean dryRun) {
        return new CleanupSettings(true, dryRun, 30, 1200, 128, 1, 8, 35.0, true, true);
    }

    private static boolean await(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (check.value()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return check.value();
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordingRepository repository = new RecordingRepository();
        private final RecordingLatch latch = new RecordingLatch();
        private final MutationJournal journal = new MutationJournal(repository, latch, 128, 16, ignored -> { });
        private final FrontierTrackingService tracking;
        private final RecordingStorage storage = new RecordingStorage();
        private final CleanupService service;

        private Fixture(CleanupSettings settings, double mspt, boolean safe) {
            journal.start();
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            tracking = new FrontierTrackingService(Map.of("world", new CoreBoundaryPolicy(0)), 0, journal, clock);
            CleanupEnvironmentPort environment = new CleanupEnvironmentPort() {
                @Override
                public double recentMspt() {
                    return mspt;
                }

                @Override
                public boolean isSafeToClear(String worldName, ChunkKey key, int minimumPlayerDistanceChunks) {
                    return safe;
                }
            };
            service = new CleanupService(settings, tracking, journal, latch, environment, storage, clock);
        }

        private CleanupCandidate candidate() {
            return new CleanupCandidate(new ChunkKey(WORLD_UUID.toString(), 10, 11), NOW.minusSeconds(3600));
        }

        @Override
        public void close() {
            journal.close();
        }
    }

    private static final class RecordingRepository implements FrontierRepository {
        private final List<FrontierMutation> applied = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void initialize() {
        }

        @Override
        public void applyBatch(List<FrontierMutation> mutations) {
            applied.addAll(mutations);
        }

        @Override
        public List<CleanupCandidate> findCleanupCandidates(String worldUuid, Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<RegionKey> findDeletedRegions(String worldUuid, int limit) {
            return List.of();
        }

        @Override
        public boolean isProtected(ChunkKey key) {
            return applied.stream().anyMatch(mutation -> mutation instanceof FrontierMutation.Protected protectedMutation
                    && protectedMutation.key().equals(key));
        }

        @Override
        public boolean isDeleted(ChunkKey key) {
            return applied.stream().anyMatch(mutation -> mutation instanceof FrontierMutation.Deleted deleted
                    && deleted.key().equals(key));
        }

        @Override
        public FrontierStats stats() {
            return new FrontierStats(0, 0, 0);
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingStorage implements StorageReclaimPort {
        private int clears;

        @Override
        public String adapterName() {
            return "test";
        }

        @Override
        public boolean supportsPhysicalReclaim() {
            return true;
        }

        @Override
        public void clearChunk(String worldName, ChunkKey key) {
            clears++;
        }

        @Override
        public void flushWorld(String worldName, String expectedWorldUuid) {
        }

        @Override
        public RegionReclaimResult reclaimEmptyRegion(String worldName, RegionKey region) {
            return RegionReclaimResult.RECLAIMED;
        }

        @Override
        public boolean primaryChunkDataPresent(String worldName, ChunkKey key) {
            return true;
        }

        @Override
        public List<ChunkKey> occupiedChunks(String worldName, RegionKey region) {
            return List.of();
        }
    }

    private static final class RecordingLatch implements SafetyLatch {
        private volatile String reason;

        @Override
        public void trip(String newReason) {
            if (reason == null) {
                reason = newReason;
            }
        }

        @Override
        public boolean isTripped() {
            return reason != null;
        }

        @Override
        public String reason() {
            return reason == null ? "none" : reason;
        }
    }
}
