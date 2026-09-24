package net.enthusia.frontier.adapter.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import net.enthusia.frontier.application.FrontierMutation;
import net.enthusia.frontier.application.FrontierRepository;
import net.enthusia.frontier.application.FrontierStats;

/** SQLite implementation of the durable frontier ledger. */
public final class SqliteFrontierRepository implements FrontierRepository, AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
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
                             + "protected = 1, protection_reason = excluded.protection_reason, deleted_at_ms = NULL")) {
            for (FrontierMutation mutation : mutations) {
                if (mutation instanceof FrontierMutation.Generated generatedMutation) {
                    bindIdentity(generated, generatedMutation);
                    generated.setLong(4, generatedMutation.observedAt().toEpochMilli());
                    generated.addBatch();
                } else if (mutation instanceof FrontierMutation.Protected protectedEntry) {
                    bindIdentity(protectedMutation, protectedEntry);
                    long observedAt = protectedEntry.observedAt().toEpochMilli();
                    protectedMutation.setLong(4, observedAt);
                    protectedMutation.setLong(5, observedAt);
                    protectedMutation.setString(6, protectedEntry.kind().name());
                    protectedMutation.addBatch();
                } else {
                    throw new IllegalArgumentException("Unsupported frontier mutation: " + mutation.getClass().getName());
                }
            }
            generated.executeBatch();
            protectedMutation.executeBatch();
            active.commit();
        } catch (SQLException | RuntimeException exception) {
            active.rollback();
            throw exception;
        } finally {
            active.setAutoCommit(originalAutoCommit);
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

    private static void bindIdentity(PreparedStatement statement, FrontierMutation mutation) throws SQLException {
        statement.setString(1, mutation.key().worldUuid());
        statement.setInt(2, mutation.key().x());
        statement.setInt(3, mutation.key().z());
    }
}
