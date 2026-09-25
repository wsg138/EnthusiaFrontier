package org.enthusia.tempchallenges.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcChallengeLedgerRecoveryTest {
    @TempDir Path temp;

    @Test
    void newerSchemaIsRejectedInsteadOfSilentlyDowngraded() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = temp.resolve("future.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO schema_meta(key,value) VALUES('schema_version','999')");
        }

        SQLException exception = assertThrows(SQLException.class, () -> new JdbcChallengeLedger(database));
        assertTrue(exception.getMessage().contains("newer than supported"));
    }

    @Test
    void creditedDragonFinalKillerIsPersistedEvenWithoutTrackedDamage() throws Exception {
        Path database = temp.resolve("dragon.sqlite");
        UUID dragon = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(database)) {
            ledger.saveDragonSession("event", dragon, List.of(), killer);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT player_uuid, damage, hits, final_blow FROM dragon_contribution")) {
            assertTrue(rs.next());
            assertEquals(killer.toString(), rs.getString("player_uuid"));
            assertEquals(0.0, rs.getDouble("damage"));
            assertEquals(0, rs.getInt("hits"));
            assertEquals(1, rs.getInt("final_blow"));
            assertTrue(!rs.next());
        }
    }
}
