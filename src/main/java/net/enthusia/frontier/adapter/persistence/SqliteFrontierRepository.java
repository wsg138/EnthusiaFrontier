package net.enthusia.frontier.adapter.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.enthusia.frontier.application.CleanupCandidate;
import net.enthusia.frontier.application.FrontierMutation;
import net.enthusia.frontier.application.FrontierRepository;
import net.enthusia.frontier.application.FrontierStats;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;

/** SQLite implementation of the durable frontier ledger. */
public final class SqliteFrontierRepository implements FrontierRepository, AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private static final int REGION_SCAN_MULTIPLIER = 32;
    private final Path databasePath;
    private Connection connection;

    public SqliteFrontierRepository(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath();
    }

    @Override
    public synchronized void initialize() throws Exception {
        if (connection != null) {
            throw new IllegalStateException("Frontier repository is already initialized");
        }
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    @Override
    public synchronized void applyBatch(List<FrontierMutation> mutations) throws SQLException {
        Objects.requireNonNull(mutations, "mutations");
        if (mutations.isEmpty()) {
            return;
        }
        Connection active = requireConnection();
        boolean originalAutoCommit = active.getAutoCommit();
        active.setAutoCommit(false);
        try (PreparedStatement generated = active.prepareStatement(
                "INSERT INTO frontier_chunk "
                        + "(world_uuid, chunk_x, chunk_z, generated_at_ms, protected, deleted_at_ms) "
                        + "VALUES (?, ?, ?, ?, 0, NULL) "
                        + "ON CONFLICT(world_uuid, chunk_x, chunk_z) DO UPDATE SET "
                        + "generated_at_ms = CASE WHEN frontier_chunk.deleted_at_ms IS NOT NULL "
                        + "THEN excluded.generated_at_ms ELSE frontier_chunk.generated_at_ms END, "
                        + "deleted_at_ms = NULL");
             PreparedStatement protectedMutation = active.prepareStatement(
                     "INSERT INTO frontier_chunk "
                             + "(world_uuid, chunk_x, chunk_z, generated_at_ms, last_activity_at_ms, "
                             + "protected, protection_reason, deleted_at_ms) "
                             + "VALUES (?, ?, ?, ?, ?, 1, ?, NULL) "
                             + "ON CONFLICT(world_uuid, chunk_x, chunk_z) DO UPDATE SET "
                             + "last_activity_at_ms = CASE "
                             + "WHEN frontier_chunk.last_activity_at_ms IS NULL "
                             + "OR excluded.last_activity_at_ms > frontier_chunk.last_activity_at_ms "
                             + "THEN excluded.last_activity_at_ms ELSE frontier_chunk.last_activity_at_ms END, "
                             + "protected = 1, protection_reason = excluded.protection_reason, deleted_at_ms = NULL");
             PreparedStatement deleted = active.prepareStatement(
                     "UPDATE frontier_chunk SET deleted_at_ms = ? "
                             + "WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? "
                             + "AND protected = 0 AND deleted_at_ms IS NULL")) {
            for (FrontierMutation mutation : mutations) {
                if (mutation instanceof FrontierMutation.Generated generatedMutation) {
                    bindIdentity(generated, generatedMutation, 1);
                    generated.setLong(4, generatedMutation.observedAt().toEpochMilli());
                    generated.addBatch();
                } else if (mutation instanceof FrontierMutation.Protected protectedEntry) {
                    bindIdentity(protectedMutation, protectedEntry, 1);
                    long observedAt = protectedEntry.observedAt().toEpochMilli();
                    protectedMutation.setLong(4, observedAt);
                    protectedMutation.setLong(5, observedAt);
                    protectedMutation.setString(6, protectedEntry.kind().name());
                    protectedMutation.addBatch();
                } else if (mutation instanceof FrontierMutation.Deleted deletedEntry) {
                    deleted.setLong(1, deletedEntry.observedAt().toEpochMilli());
                    bindIdentity(deleted, deletedEntry, 2);
                    deleted.addBatch();
                } else {
                    throw new IllegalArgumentException("Unsupported frontier mutation: " + mutation.getClass().getName());
                }
            }
            generated.executeBatch();
            protectedMutation.executeBatch();
            deleted.executeBatch();
            active.commit();
        } catch (SQLException | RuntimeException exception) {
            active.rollback();
            throw exception;
        } finally {
            active.setAutoCommit(originalAutoCommit);
        }
    }

    @Override
    public synchronized List<CleanupCandidate> findCleanupCandidates(
            String worldUuid, Instant cutoff, int limit) throws SQLException {
        requirePositiveLimit(limit);
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(cutoff, "cutoff");
        List<CleanupCandidate> result = new ArrayList<>(limit);
        try (PreparedStatement statement = requireConnection().prepareStatement(
                "SELECT chunk_x, chunk_z, generated_at_ms FROM frontier_chunk "
                        + "WHERE world_uuid = ? AND protected = 0 AND deleted_at_ms IS NULL "
                        + "AND generated_at_ms <= ? ORDER BY generated_at_ms, chunk_x, chunk_z LIMIT ?")) {
            statement.setString(1, worldUuid);
            statement.setLong(2, cutoff.toEpochMilli());
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ChunkKey key = new ChunkKey(worldUuid, rows.getInt(1), rows.getInt(2));
                    result.add(new CleanupCandidate(key, Instant.ofEpochMilli(rows.getLong(3))));
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<RegionKey> findDeletedRegions(String worldUuid, int limit) throws SQLException {
        requirePositiveLimit(limit);
        Objects.requireNonNull(worldUuid, "worldUuid");
        Set<RegionKey> regions = new LinkedHashSet<>();
        int rowLimit = Math.multiplyExact(limit, REGION_SCAN_MULTIPLIER);
        try (PreparedStatement statement = requireConnection().prepareStatement(
                "SELECT chunk_x, chunk_z FROM frontier_chunk "
                        + "WHERE world_uuid = ? AND deleted_at_ms IS NOT NULL "
                        + "ORDER BY deleted_at_ms DESC LIMIT ?")) {
            statement.setString(1, worldUuid);
            statement.setInt(2, rowLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next() && regions.size() < limit) {
                    ChunkKey key = new ChunkKey(worldUuid, rows.getInt(1), rows.getInt(2));
                    regions.add(RegionKey.fromChunk(key));
                }
            }
        }
        return List.copyOf(regions);
    }

    @Override
    public synchronized boolean isProtected(ChunkKey key) throws SQLException {
        Objects.requireNonNull(key, "key");
        try (PreparedStatement statement = requireConnection().prepareStatement(
                "SELECT 1 FROM frontier_chunk WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? "
                        + "AND protected = 1 AND deleted_at_ms IS NULL LIMIT 1")) {
            bindKey(statement, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public synchronized boolean isDeleted(ChunkKey key) throws SQLException {
        Objects.requireNonNull(key, "key");
        try (PreparedStatement statement = requireConnection().prepareStatement(
                "SELECT 1 FROM frontier_chunk WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? "
                        + "AND deleted_at_ms IS NOT NULL LIMIT 1")) {
            bindKey(statement, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public synchronized FrontierStats stats() throws SQLException {
        try (Statement statement = requireConnection().createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT "
                             + "COALESCE(SUM(CASE WHEN protected = 0 AND deleted_at_ms IS NULL THEN 1 ELSE 0 END), 0), "
                             + "COALESCE(SUM(CASE WHEN protected = 1 AND deleted_at_ms IS NULL THEN 1 ELSE 0 END), 0), "
                             + "COALESCE(SUM(CASE WHEN deleted_at_ms IS NOT NULL THEN 1 ELSE 0 END), 0) "
                             + "FROM frontier_chunk")) {
            if (!result.next()) {
                return new FrontierStats(0, 0, 0);
            }
            return new FrontierStats(result.getLong(1), result.getLong(2), result.getLong(3));
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection == null) {
            return;
        }
        connection.close();
        connection = null;
    }

    private void migrate() throws SQLException {
        Connection active = requireConnection();
        int version;
        try (Statement statement = active.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            version = result.next() ? result.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("Frontier database schema " + version
                    + " is newer than supported version " + SCHEMA_VERSION);
        }
        if (version == 0) {
            try (Statement statement = active.createStatement()) {
                statement.execute("CREATE TABLE frontier_chunk ("
                        + "world_uuid TEXT NOT NULL, "
                        + "chunk_x INTEGER NOT NULL, "
                        + "chunk_z INTEGER NOT NULL, "
                        + "generated_at_ms INTEGER NOT NULL, "
                        + "last_activity_at_ms INTEGER, "
                        + "protected INTEGER NOT NULL DEFAULT 0 CHECK (protected IN (0, 1)), "
                        + "protection_reason TEXT, "
                        + "deleted_at_ms INTEGER, "
                        + "PRIMARY KEY (world_uuid, chunk_x, chunk_z))");
                statement.execute("CREATE INDEX frontier_cleanup_candidates "
                        + "ON frontier_chunk(protected, deleted_at_ms, generated_at_ms)");
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        }
    }

    private Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("Frontier repository is not initialized");
        }
        return connection;
    }

    private static void bindIdentity(
            PreparedStatement statement, FrontierMutation mutation, int startIndex) throws SQLException {
        statement.setString(startIndex, mutation.key().worldUuid());
        statement.setInt(startIndex + 1, mutation.key().x());
        statement.setInt(startIndex + 2, mutation.key().z());
    }

    private static void bindKey(PreparedStatement statement, ChunkKey key) throws SQLException {
        statement.setString(1, key.worldUuid());
        statement.setInt(2, key.x());
        statement.setInt(3, key.z());
    }

    private static void requirePositiveLimit(int limit) {
        if (limit < 1 || limit > 4096) {
            throw new IllegalArgumentException("limit must be within 1..4096");
        }
    }
}
