package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.enthusia.frontier.domain.RegionKey;
import org.junit.jupiter.api.Test;

class FrontierTrackingServiceTest {
    private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000138");
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void generationRecordsOnlyManagedChunksInConfiguredWorlds() {
        RecordingRepository repository = new RecordingRepository();
        MutationJournal journal = journal(repository);
        FrontierTrackingService service = new FrontierTrackingService(
                Map.of("world", new CoreBoundaryPolicy(16)), 0, journal, Clock.fixed(NOW, ZoneOffset.UTC));

        journal.start();
        assertFalse(service.recordGenerated("other", WORLD_UUID, 2, 0));
        assertFalse(service.recordGenerated("world", WORLD_UUID, 0, 0));
        assertTrue(service.recordGenerated("world", WORLD_UUID, 2, 0));
        journal.close();

        assertEquals(1, repository.applied.size());
        FrontierMutation.Generated generated = (FrontierMutation.Generated) repository.applied.get(0);
        assertEquals(WORLD_UUID.toString(), generated.key().worldUuid());
        assertEquals(2, generated.key().x());
        assertEquals(NOW, generated.observedAt());
        assertEquals(1, service.worldPolicies().size());
    }

    @Test
    void activityProtectsMemoryBeforeDurableWriteAndPreservesRadius() {
        RecordingRepository repository = new RecordingRepository();
        MutationJournal journal = journal(repository);
        FrontierTrackingService service = new FrontierTrackingService(
                Map.of("world", new CoreBoundaryPolicy(0)), 1, journal, Clock.fixed(NOW, ZoneOffset.UTC));

        journal.start();
        assertEquals(0, service.recordActivity("other", WORLD_UUID, 10, 10, ActivityKind.BLOCK_PLACE));
        assertEquals(9, service.recordActivity("world", WORLD_UUID, 10, 10, ActivityKind.BLOCK_PLACE));
        assertTrue(service.isProtectedInMemory(new ChunkKey(WORLD_UUID.toString(), 9, 9)));
        assertTrue(service.isProtectedInMemory(new ChunkKey(WORLD_UUID.toString(), 11, 11)));
        assertFalse(service.isProtectedInMemory(new ChunkKey(WORLD_UUID.toString(), 12, 12)));
        journal.close();

        assertEquals(9, repository.applied.size());
        for (FrontierMutation mutation : repository.applied) {
            FrontierMutation.Protected protectedMutation = (FrontierMutation.Protected) mutation;
            assertEquals(ActivityKind.BLOCK_PLACE, protectedMutation.kind());
            assertEquals(NOW, protectedMutation.observedAt());
        }
    }

    @Test
    void constructorRejectsInvalidProtectionRadius() {
        MutationJournal journal = journal(new RecordingRepository());
        Map<String, CoreBoundaryPolicy> worlds = Map.of("world", new CoreBoundaryPolicy(0));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FrontierTrackingService(worlds, -1, journal, clock));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FrontierTrackingService(worlds, 33, journal, clock));
    }

    private static MutationJournal journal(FrontierRepository repository) {
        return new MutationJournal(repository, new NoopLatch(), 128, 32, ignored -> { });
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
            return false;
        }

        @Override
        public boolean isDeleted(ChunkKey key) {
            return false;
        }

        @Override
        public FrontierStats stats() {
            return new FrontierStats(0, 0, 0);
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopLatch implements SafetyLatch {
        @Override
        public void trip(String reason) {
        }

        @Override
        public boolean isTripped() {
            return false;
        }

        @Override
        public String reason() {
            return "none";
        }
    }
}
