package net.enthusia.frontier.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import net.enthusia.frontier.application.SafetyLatch;
import net.enthusia.frontier.domain.ChunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteGenerationReadinessAdapterTest {
    private static final String WORLD = "00000000-0000-0000-0000-000000000010";

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedReadinessSurvivesAdapterRestart() throws Exception {
        Path database = initializeSchema();
        RecordingLatch latch = new RecordingLatch();
        ChunkKey key = new ChunkKey(WORLD, 7, -3);

        try (SqliteGenerationReadinessAdapter first =
                new SqliteGenerationReadinessAdapter(database, latch, Clock.systemUTC())) {
            first.initialize(List.of(WORLD));
            assertFalse(first.isReady(key));
            first.markReady(key).join();
            assertTrue(first.isReady(key));
            assertEquals(1, first.cachedChunks());
        }

        try (SqliteGenerationReadinessAdapter second =
                new SqliteGenerationReadinessAdapter(database, latch, Clock.systemUTC())) {
            second.initialize(List.of(WORLD));
            assertTrue(second.isReady(key));
            assertEquals(1, second.cachedChunks());
        }
        assertFalse(latch.isTripped());
    }

    @Test
    void adoptLoadedPersistsAndForgetInvalidatesHotCache() throws Exception {
        Path database = initializeSchema();
        RecordingLatch latch = new RecordingLatch();
        ChunkKey first = new ChunkKey(WORLD, 1, 1);
        ChunkKey second = new ChunkKey(WORLD, 2, 2);

        try (SqliteGenerationReadinessAdapter adapter =
                new SqliteGenerationReadinessAdapter(database, latch, Clock.systemUTC())) {
            adapter.initialize(List.of(WORLD));
            adapter.adoptLoaded(List.of(first, second));
            assertTrue(adapter.isReady(first));
            assertTrue(adapter.isReady(second));
            assertEquals(2, adapter.cachedChunks());
            adapter.forget(first);
            assertFalse(adapter.isReady(first));
            assertTrue(adapter.isReady(second));
            assertEquals(1, adapter.cachedChunks());
        }

        try (SqliteGenerationReadinessAdapter restarted =
                new SqliteGenerationReadinessAdapter(database, latch, Clock.systemUTC())) {
            restarted.initialize(List.of(WORLD));
            assertTrue(restarted.isReady(first));
            assertTrue(restarted.isReady(second));
        }
        assertFalse(latch.isTripped());
    }

    @Test
    void emptyAdoptionIsNoOp() throws Exception {
        Path database = initializeSchema();
        RecordingLatch latch = new RecordingLatch();
        try (SqliteGenerationReadinessAdapter adapter =
                new SqliteGenerationReadinessAdapter(database, latch, Clock.systemUTC())) {
            adapter.initialize(List.of(WORLD));
            adapter.adoptLoaded(List.of());
            assertEquals(0, adapter.cachedChunks());
        }
        assertFalse(latch.isTripped());
    }

    private Path initializeSchema() throws Exception {
        Path database = temporaryDirectory.resolve("frontier.db");
        try (SqliteFrontierRepository repository = new SqliteFrontierRepository(database)) {
            repository.initialize();
        }
        return database;
    }

    private static final class RecordingLatch implements SafetyLatch {
        private boolean tripped;
        private String reason = "";

        @Override
        public void trip(String newReason) {
            tripped = true;
            reason = newReason;
        }

        @Override
        public boolean isTripped() {
            return tripped;
        }

        @Override
        public String reason() {
            return reason;
        }
    }
}
