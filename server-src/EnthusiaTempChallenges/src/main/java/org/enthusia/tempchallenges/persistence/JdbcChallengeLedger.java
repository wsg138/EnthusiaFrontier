package org.enthusia.tempchallenges.persistence;

import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ClaimDecision;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.domain.DragonContribution;
import org.enthusia.tempchallenges.domain.EventState;
import org.enthusia.tempchallenges.domain.WinnerRecord;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcChallengeLedger implements ChallengeLedger {
    private static final int SCHEMA_VERSION = 1;
    private final Path database;

    public JdbcChallengeLedger(Path database) throws Exception {
        this.database = database.toAbsolutePath().normalize();
        if (this.database.getParent() != null) Files.createDirectories(this.database.getParent());
        Class.forName("org.sqlite.JDBC");
        migrate();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void migrate() throws SQLException {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            Integer existingVersion = readSchemaVersion(c);
            if (existingVersion != null && existingVersion > SCHEMA_VERSION) {
                throw new SQLException("Challenge ledger schema " + existingVersion +
                        " is newer than supported schema " + SCHEMA_VERSION + "; refusing downgrade.");
            }

            s.execute("CREATE TABLE IF NOT EXISTS challenge_winner (event_id TEXT NOT NULL, challenge_id TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, platform TEXT NOT NULL, claimed_at TEXT NOT NULL, signal_id TEXT NOT NULL, signal TEXT NOT NULL, PRIMARY KEY(event_id, challenge_id))");
            s.execute("CREATE TABLE IF NOT EXISTS attempt_journal (seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL, challenge_id TEXT NOT NULL, signal_id TEXT NOT NULL, signal TEXT NOT NULL, actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL, platform TEXT NOT NULL, occurred_at TEXT NOT NULL, eligibility TEXT NOT NULL, decision TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', UNIQUE(event_id, challenge_id, signal_id))");
            s.execute("CREATE TABLE IF NOT EXISTS reward_delivery (event_id TEXT NOT NULL, challenge_id TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, updated_at TEXT NOT NULL, PRIMARY KEY(event_id, challenge_id, kind))");
            s.execute("CREATE TABLE IF NOT EXISTS dragon_contribution (event_id TEXT NOT NULL, dragon_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, damage REAL NOT NULL, hits INTEGER NOT NULL, first_hit_at TEXT NOT NULL, last_hit_at TEXT NOT NULL, final_blow INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(event_id, dragon_uuid, player_uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS revocation_history (id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL, challenge_id TEXT NOT NULL, winner_uuid TEXT NOT NULL, actor TEXT NOT NULL, reason TEXT NOT NULL, revoked_at TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO schema_meta(key,value) VALUES('schema_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, Integer.toString(SCHEMA_VERSION));
                ps.executeUpdate();
            }
        }
    }

    private Integer readSchemaVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM schema_meta WHERE key='schema_version'");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            String raw = rs.getString(1);
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw new SQLException("Invalid challenge ledger schema version: " + raw, exception);
            }
        }
    }

    @Override
    public ClaimResult attempt(ChallengeAttempt attempt) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO attempt_journal(event_id,challenge_id,signal_id,signal,actor_uuid,actor_name,platform,occurred_at,eligibility,decision,detail) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, attempt.eventId());
                    ps.setString(2, attempt.challenge().id());
                    ps.setString(3, attempt.signalId());
                    ps.setString(4, attempt.signal().toString());
                    ps.setString(5, attempt.actor().uuid().toString());
                    ps.setString(6, attempt.actor().name());
                    ps.setString(7, attempt.actor().platform().name());
                    ps.setString(8, attempt.occurredAt().toString());
                    ps.setString(9, attempt.eligibility().reason());
                    ps.setString(10, "RECEIVED");
                    ps.setString(11, attempt.detail());
                    inserted = ps.executeUpdate();
                }
                if (inserted == 0) {
                    c.rollback();
                    return ClaimResult.of(ClaimDecision.DUPLICATE_SIGNAL,
                            winner(c, attempt.eventId(), attempt.challenge().id()).orElse(null),
                            "signal already journaled");
                }
                if (attempt.challenge().locked()) {
                    return rejectAndCommit(c, attempt, ClaimDecision.LOCKED, attempt.challenge().lockReason());
                }
                if (attempt.eventState() != EventState.ACTIVE) {
                    return rejectAndCommit(c, attempt, ClaimDecision.EVENT_NOT_ACTIVE, attempt.eventState().name());
                }
                if (!attempt.eligibility().eligible()) {
                    return rejectAndCommit(c, attempt, ClaimDecision.INELIGIBLE, attempt.eligibility().reason());
                }

                int claimed;
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO challenge_winner(event_id,challenge_id,uuid,name,platform,claimed_at,signal_id,signal) VALUES(?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, attempt.eventId());
                    ps.setString(2, attempt.challenge().id());
                    ps.setString(3, attempt.actor().uuid().toString());
                    ps.setString(4, attempt.actor().name());
                    ps.setString(5, attempt.actor().platform().name());
                    ps.setString(6, attempt.occurredAt().toString());
                    ps.setString(7, attempt.signalId());
                    ps.setString(8, attempt.signal().toString());
                    claimed = ps.executeUpdate();
                }
                if (claimed == 1) {
                    updateJournalDecision(c, attempt, "CLAIMED", attempt.detail());
                    for (String kind : new String[]{"LUCKPERMS", "TAG", "ADVANCEMENT"}) {
                        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO reward_delivery(event_id,challenge_id,kind,state,attempts,updated_at) VALUES(?,?,?,'PENDING',0,?)")) {
                            ps.setString(1, attempt.eventId());
                            ps.setString(2, attempt.challenge().id());
                            ps.setString(3, kind);
                            ps.setString(4, Instant.now().toString());
                            ps.executeUpdate();
                        }
                    }
                    WinnerRecord record = new WinnerRecord(attempt.eventId(), attempt.challenge().id(),
                            attempt.actor().uuid(), attempt.actor().name(), attempt.actor().platform(),
                            attempt.occurredAt(), attempt.signalId(), attempt.signal().toString());
                    c.commit();
                    return ClaimResult.of(ClaimDecision.CLAIMED, record, "durable claim committed");
                }

                WinnerRecord existing = winner(c, attempt.eventId(), attempt.challenge().id()).orElseThrow();
                updateJournalDecision(c, attempt, "ALREADY_CLAIMED", "winner=" + existing.uuid());
                c.commit();
                return ClaimResult.of(ClaimDecision.ALREADY_CLAIMED, existing, "winner already exists");
            } catch (SQLException | RuntimeException exception) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        }
    }

    private ClaimResult rejectAndCommit(Connection c, ChallengeAttempt attempt, ClaimDecision decision, String reason) throws SQLException {
        updateJournalDecision(c, attempt, decision.name(), reason);
        c.commit();
        return ClaimResult.of(decision, null, reason);
    }

    private void updateJournalDecision(Connection c, ChallengeAttempt attempt, String decision, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE attempt_journal SET decision=?, detail=? WHERE event_id=? AND challenge_id=? AND signal_id=?")) {
            ps.setString(1, decision);
            ps.setString(2, detail == null ? "" : detail);
            ps.setString(3, attempt.eventId());
            ps.setString(4, attempt.challenge().id());
            ps.setString(5, attempt.signalId());
            ps.executeUpdate();
        }
    }

    @Override public Optional<WinnerRecord> winner(String eventId, String challengeId) throws SQLException {
        try (Connection c = open()) { return winner(c, eventId, challengeId); }
    }

    private Optional<WinnerRecord> winner(Connection c, String eventId, String challengeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM challenge_winner WHERE event_id=? AND challenge_id=?")) {
            ps.setString(1, eventId);
            ps.setString(2, challengeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readWinner(rs));
            }
        }
    }

    @Override public Map<String, WinnerRecord> winners(String eventId) throws SQLException {
        Map<String, WinnerRecord> result = new LinkedHashMap<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM challenge_winner WHERE event_id=? ORDER BY claimed_at")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WinnerRecord w = readWinner(rs);
                    result.put(w.challengeId(), w);
                }
            }
        }
        return result;
    }

    private WinnerRecord readWinner(ResultSet rs) throws SQLException {
        return new WinnerRecord(rs.getString("event_id"), rs.getString("challenge_id"),
                UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                ActorIdentity.Platform.valueOf(rs.getString("platform")), Instant.parse(rs.getString("claimed_at")),
                rs.getString("signal_id"), rs.getString("signal"));
    }

    @Override public boolean revoke(String eventId, String challengeId, UUID expectedWinner, String actor, String reason) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                Optional<WinnerRecord> current = winner(c, eventId, challengeId);
                if (current.isEmpty() || !current.get().uuid().equals(expectedWinner)) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO revocation_history(event_id,challenge_id,winner_uuid,actor,reason,revoked_at) VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, eventId);
                    ps.setString(2, challengeId);
                    ps.setString(3, expectedWinner.toString());
                    ps.setString(4, actor);
                    ps.setString(5, reason == null ? "operator-revocation" : reason);
                    ps.setString(6, Instant.now().toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM challenge_winner WHERE event_id=? AND challenge_id=? AND uuid=?")) {
                    ps.setString(1, eventId);
                    ps.setString(2, challengeId);
                    ps.setString(3, expectedWinner.toString());
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        return false;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE reward_delivery SET state='REVOKED', updated_at=? WHERE event_id=? AND challenge_id=?")) {
                    ps.setString(1, Instant.now().toString());
                    ps.setString(2, eventId);
                    ps.setString(3, challengeId);
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw exception;
            }
        }
    }

    @Override public void markReward(String eventId, String challengeId, String kind, String state, String error) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("INSERT INTO reward_delivery(event_id,challenge_id,kind,state,attempts,last_error,updated_at) VALUES(?,?,?,?,1,?,?) ON CONFLICT(event_id,challenge_id,kind) DO UPDATE SET state=excluded.state, attempts=reward_delivery.attempts+1, last_error=excluded.last_error, updated_at=excluded.updated_at")) {
            ps.setString(1, eventId);
            ps.setString(2, challengeId);
            ps.setString(3, kind);
            ps.setString(4, state);
            ps.setString(5, error);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    @Override public Map<String, String> pendingRewards(String eventId, String challengeId) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT kind,state FROM reward_delivery WHERE event_id=? AND challenge_id=? AND state NOT IN ('DELIVERED','REVOKED')")) {
            ps.setString(1, eventId);
            ps.setString(2, challengeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    @Override
    public void saveDragonSession(String eventId, UUID dragonUuid, Collection<DragonContribution> contributions,
                                  UUID finalKiller) throws SQLException {
        Map<UUID, DragonContribution> rows = new LinkedHashMap<>();
        for (DragonContribution contribution : contributions) {
            rows.put(contribution.playerUuid(), contribution);
        }
        if (finalKiller != null && !rows.containsKey(finalKiller)) {
            Instant now = Instant.now();
            rows.put(finalKiller, new DragonContribution(finalKiller, 0.0, 0, now, now));
        }

        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dragon_contribution(event_id,dragon_uuid,player_uuid,damage,hits,first_hit_at,last_hit_at,final_blow) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(event_id,dragon_uuid,player_uuid) DO UPDATE SET damage=excluded.damage,hits=excluded.hits,first_hit_at=excluded.first_hit_at,last_hit_at=excluded.last_hit_at,final_blow=excluded.final_blow")) {
                for (DragonContribution d : rows.values()) {
                    ps.setString(1, eventId);
                    ps.setString(2, dragonUuid.toString());
                    ps.setString(3, d.playerUuid().toString());
                    ps.setDouble(4, d.damage());
                    ps.setInt(5, d.hits());
                    ps.setString(6, d.firstHitAt().toString());
                    ps.setString(7, d.lastHitAt().toString());
                    ps.setInt(8, d.playerUuid().equals(finalKiller) ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException exception) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw exception;
            }
        }
    }

    @Override public Path exportCsv(String eventId, Path destination) throws Exception {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            writer.write("challenge_id,uuid,name,platform,claimed_at,signal_id,signal\n");
            for (WinnerRecord w : winners(eventId).values()) {
                writer.write(csv(w.challengeId()) + ',' + csv(w.uuid().toString()) + ',' + csv(w.name()) + ',' +
                        csv(w.platform().name()) + ',' + csv(w.claimedAt().toString()) + ',' + csv(w.signalId()) + ',' +
                        csv(w.signal()) + '\n');
            }
        }
        return destination;
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
