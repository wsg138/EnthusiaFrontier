package net.enthusia.frontier.adapter.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import net.enthusia.frontier.application.GenerationReadinessPort;
import net.enthusia.frontier.application.SafetyLatch;
import net.enthusia.frontier.domain.ChunkKey;

/**
 * Restart-safe generated-chunk index backed by the same SQLite ledger.
 * Movement checks are memory-only; writes run on a dedicated serial executor and
 * complete only after SQLite FULL-synchronous durability succeeds.
 */
public final class SqliteGenerationReadinessAdapter implements GenerationReadinessPort, AutoCloseable {
    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final Path databasePath;
    private final SafetyLatch safetyLatch;
    private final Clock clock;
    private final Map<String, Set<Long>> readyByWorld = new ConcurrentHashMap<>();
    private final ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("EnthusiaFrontier-Readiness", 0).factory());
    private Connection connection;

    public SqliteGenerationReadinessAdapter(Path databasePath, SafetyLatch safetyLatch, Clock clock) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath();
        this.safetyLatch = Objects.requireNonNull(safetyLatch, "safetyLatch");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Called during plugin startup, after the primary repository schema is initialized. */
    public synchronized void initialize(Collection<String> worldUuids) throws Exception {
        if (connection != null) {
            throw new IllegalStateException("generation readiness adapter is already initialized");
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        for (String worldUuid : worldUuids) {
            loadWorld(Objects.requireNonNull(worldUuid, "worldUuid"));
        }
    }

    /** Durably adopts already-loaded managed chunks during startup without runtime generation. */
    public synchronized void adoptLoaded(Collection<ChunkKey> keys) throws Exception {
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            return;
        }
        Connection active = requireConnection();
        boolean autoCommit = active.getAutoCommit();
        active.setAutoCommit(false);
        try (PreparedStatement statement = generatedUpsert(active)) {
            Instant now = Instant.now(clock);
            List<ChunkKey> accepted = new ArrayList<>(keys.size());
            for (ChunkKey key : keys) {
                bindGenerated(statement, key, now);
                statement.addBatch();
                accepted.add(key);
            }
            statement.executeBatch();
            active.commit();
            accepted.forEach(this::cacheReady);
        } catch (Exception exception) {
            active.rollback();
            safetyLatch.trip("could not durably adopt loaded frontier chunks");
            throw exception;
        } finally {
            active.setAutoCommit(autoCommit);
        }
    }

    @Override
    public boolean isReady(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Set<Long> world = readyByWorld.get(key.worldUuid());
        return world != null && world.contains(pack(key.x(), key.z()));
    }

    @Override
    public CompletableFuture<Void> markReady(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        if (isReady(key)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                persistReady(key);
                cacheReady(key);
            } catch (Exception exception) {
                safetyLatch.trip("frontier generation readiness durable write failed");
                throw new CompletionException(exception);
            }
        }, writer);
    }

    /** Invalidates memory immediately after logical storage deletion. */
    public void forget(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Set<Long> world = readyByWorld.get(key.worldUuid());
        if (world != null) {
            world.remove(pack(key.x(), key.z()));
        }
    }

    public int cachedChunks() {
        return readyByWorld.values().stream().mapToInt(Set::size).sum();
    }

    @Override
    public void close() throws SQLException {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                safetyLatch.trip("generation readiness writer did not stop cleanly");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            safetyLatch.trip("generation readiness writer shutdown was interrupted");
            writer.shutdownNow();
        }
        synchronized (this) {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
    }

    private synchronized void loadWorld(String worldUuid) throws Exception {
        Set<Long> ready = readyByWorld.computeIfAbsent(worldUuid, ignored -> ConcurrentHashMap.newKeySet());
        try (PreparedStatement statement = requireConnection().prepareStatement(
                "SELECT chunk_x, chunk_z FROM frontier_chunk "
                        + "WHERE world_uuid = ? AND deleted_at_ms IS NULL")) {
            statement.setString(1, worldUuid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ready.add(pack(rows.getInt(1), rows.getInt(2)));
                }
            }
        }
    }

    private synchronized void persistReady(ChunkKey key) throws Exception {
        try (PreparedStatement statement = generatedUpsert(requireConnection())) {
            bindGenerated(statement, key, Instant.now(clock));
            statement.executeUpdate();
        }
    }

    private static PreparedStatement generatedUpsert(Connection connection) throws Exception {
        return connection.prepareStatement(
                "INSERT INTO frontier_chunk "
                        + "(world_uuid, chunk_x, chunk_z, generated_at_ms, protected, deleted_at_ms, reclaim_intent_at_ms) "
                        + "VALUES (?, ?, ?, ?, 0, NULL, NULL) "
                        + "ON CONFLICT(world_uuid, chunk_x, chunk_z) DO UPDATE SET "
                        + "generated_at_ms = CASE WHEN frontier_chunk.deleted_at_ms IS NOT NULL "
                        + "THEN excluded.generated_at_ms ELSE frontier_chunk.generated_at_ms END, "
                        + "deleted_at_ms = NULL, reclaim_intent_at_ms = NULL");
    }

    private static void bindGenerated(PreparedStatement statement, ChunkKey key, Instant observedAt) throws Exception {
        statement.setString(1, key.worldUuid());
        statement.setInt(2, key.x());
        statement.setInt(3, key.z());
        statement.setLong(4, observedAt.toEpochMilli());
    }

    private void cacheReady(ChunkKey key) {
        readyByWorld.computeIfAbsent(key.worldUuid(), ignored -> ConcurrentHashMap.newKeySet())
                .add(pack(key.x(), key.z()));
    }

    private Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("generation readiness adapter is not initialized");
        }
        return connection;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
