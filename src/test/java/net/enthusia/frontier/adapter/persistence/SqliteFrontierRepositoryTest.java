package net.enthusia.frontier.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.enthusia.frontier.application.CleanupCandidate;
import net.enthusia.frontier.application.FrontierMutation;
import net.enthusia.frontier.application.FrontierStats;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteFrontierRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generationProtectionDeletionAndCandidateQueriesAreDurable() throws Exception {
        Path database = temporaryDirectory.resolve("frontier.db");
        ChunkKey temporary = new ChunkKey("world-a", 1, 2);
        ChunkKey protectedChunk = new ChunkKey("world-a", 3, 4);
        ChunkKey deletedNegative = new ChunkKey("world-a", -33, -65);
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = first.plusSeconds(60);

        try (SqliteFrontierRepository repository = new SqliteFrontierRepository(database)) {
            repository.initialize();
            repository.applyBatch(List.of(
                    new FrontierMutation.Generated(temporary, first),
                    new FrontierMutation.Generated(temporary, later),
                    new FrontierMutation.Generated(protectedChunk, first),
                    new FrontierMutation.Protected(protectedChunk, later, ActivityKind.BLOCK_PLACE),
                    new FrontierMutation.Generated(protectedChunk, later),
                    new FrontierMutation.Generated(deletedNegative, first)));
            CleanupCandidate reservedDeleted = repository.reserveCleanupCandidates(
                    "world-a", later.plusSeconds(1), 10, later.plusSeconds(2)).stream()
                    .filter(candidate -> candidate.key().equals(deletedNegative))
                    .findFirst()
                    .orElseThrow();
            assertTrue(reservedDeleted.hasReclaimIntent());
            repository.applyBatch(List.of(new FrontierMutation.Deleted(deletedNegative, later.plusSeconds(3))));

            FrontierStats stats = repository.stats();
            assertEquals(1, stats.temporaryChunks());
            assertEquals(1, stats.protectedChunks());
            assertEquals(1, stats.deletedChunks());
            assertTrue(repository.isProtected(protectedChunk));
            assertFalse(repository.isProtected(temporary));
            assertTrue(repository.isDeleted(deletedNegative));

            List<CleanupCandidate> candidates = repository.findCleanupCandidates(
                    "world-a", later.plusSeconds(4), 10);
            assertTrue(candidates.isEmpty(), "temporary chunk was reserved by the earlier destructive scan");
            assertEquals(List.of(new RegionKey("world-a", -2, -3)), repository.findDeletedRegions("world-a", 10));
        }

        try (SqliteFrontierRepository reopened = new SqliteFrontierRepository(database)) {
            reopened.initialize();
            assertTrue(reopened.isProtected(protectedChunk));
            assertTrue(reopened.isDeleted(deletedNegative));
            reopened.applyBatch(List.of(new FrontierMutation.Generated(deletedNegative, later.plusSeconds(5))));
            assertFalse(reopened.isDeleted(deletedNegative));
            assertEquals(2, reopened.stats().temporaryChunks());
        }
    }

    @Test
    void reclaimIntentIsCommittedBeforeReturnAndRecoveredAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("intent.db");
        ChunkKey candidateKey = new ChunkKey("world", 40, -41);
        Instant generated = Instant.parse("2026-01-01T00:00:00Z");
        Instant reserved = generated.plusSeconds(120);

        try (SqliteFrontierRepository repository = new SqliteFrontierRepository(database)) {
            repository.initialize();
            repository.applyBatch(List.of(new FrontierMutation.Generated(candidateKey, generated)));
            List<CleanupCandidate> selected = repository.reserveCleanupCandidates(
                    "world", generated.plusSeconds(60), 10, reserved);
            assertEquals(1, selected.size());
            assertEquals(candidateKey, selected.getFirst().key());
            assertEquals(reserved, selected.getFirst().reclaimIntentAt());
            assertTrue(repository.findCleanupCandidates("world", reserved, 10).isEmpty());
        }

        try (SqliteFrontierRepository reopened = new SqliteFrontierRepository(database)) {
            reopened.initialize();
            List<CleanupCandidate> recovered = reopened.reserveCleanupCandidates(
                    "world", reserved.plusSeconds(60), 10, reserved.plusSeconds(1));
            assertEquals(1, recovered.size());
            assertEquals(candidateKey, recovered.getFirst().key());
            assertEquals(reserved, recovered.getFirst().reclaimIntentAt());
            assertNotNull(recovered.getFirst().reclaimIntentAt());

            reopened.applyBatch(List.of(new FrontierMutation.Protected(
                    candidateKey, reserved.plusSeconds(2), ActivityKind.BLOCK_PLACE)));
            assertTrue(reopened.isProtected(candidateKey));
            assertTrue(reopened.reserveCleanupCandidates(
                    "world", reserved.plusSeconds(120), 10, reserved.plusSeconds(3)).isEmpty());
        }
    }

    @Test
    void candidateCutoffAndLimitAreFailClosed() throws Exception {
        Path database = temporaryDirectory.resolve("limits.db");
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        try (SqliteFrontierRepository repository = new SqliteFrontierRepository(database)) {
            repository.initialize();
            repository.applyBatch(List.of(
                    new FrontierMutation.Generated(new ChunkKey("world", 1, 0), first),
                    new FrontierMutation.Generated(new ChunkKey("world", 2, 0), first.plusSeconds(60))));

            assertEquals(1, repository.findCleanupCandidates("world", first.plusSeconds(1), 10).size());
            assertEquals(1, repository.findCleanupCandidates("world", first.plusSeconds(120), 1).size());
        }
    }
}
