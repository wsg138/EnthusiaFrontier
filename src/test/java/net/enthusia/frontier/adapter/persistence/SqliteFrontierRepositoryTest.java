package net.enthusia.frontier.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.enthusia.frontier.application.FrontierMutation;
import net.enthusia.frontier.application.FrontierStats;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteFrontierRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generationIsIdempotentAndProtectionIsMonotonic() throws Exception {
        Path database = temporaryDirectory.resolve("frontier.db");
        ChunkKey temporary = new ChunkKey("world-a", 1, 2);
        ChunkKey protectedChunk = new ChunkKey("world-a", 3, 4);
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = first.plusSeconds(60);

        try (SqliteFrontierRepository repository = new SqliteFrontierRepository(database)) {
            repository.initialize();
            repository.applyBatch(List.of(
                    new FrontierMutation.Generated(temporary, first),
                    new FrontierMutation.Generated(temporary, later),
                    new FrontierMutation.Generated(protectedChunk, first),
                    new FrontierMutation.Protected(protectedChunk, later, ActivityKind.BLOCK_PLACE),
                    new FrontierMutation.Generated(protectedChunk, later)));

            FrontierStats stats = repository.stats();
            assertEquals(1, stats.temporaryChunks());
            assertEquals(1, stats.protectedChunks());
            assertEquals(0, stats.deletedChunks());
        }

        try (SqliteFrontierRepository reopened = new SqliteFrontierRepository(database)) {
            reopened.initialize();
            FrontierStats stats = reopened.stats();
            assertEquals(1, stats.temporaryChunks());
            assertEquals(1, stats.protectedChunks());
        }
    }
}
