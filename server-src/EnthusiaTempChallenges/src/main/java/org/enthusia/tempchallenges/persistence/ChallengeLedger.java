package org.enthusia.tempchallenges.persistence;

import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.domain.DragonContribution;
import org.enthusia.tempchallenges.domain.WinnerRecord;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ChallengeLedger extends AutoCloseable {
    ClaimResult attempt(ChallengeAttempt attempt) throws SQLException;
    Optional<WinnerRecord> winner(String eventId, String challengeId) throws SQLException;
    Map<String, WinnerRecord> winners(String eventId) throws SQLException;
    boolean revoke(String eventId, String challengeId, UUID expectedWinner, String actor, String reason) throws SQLException;
    void markReward(String eventId, String challengeId, String kind, String state, String error) throws SQLException;
    Map<String, String> pendingRewards(String eventId, String challengeId) throws SQLException;
    void saveDragonSession(String eventId, UUID dragonUuid, Collection<DragonContribution> contributions, UUID finalKiller) throws SQLException;
    Path exportCsv(String eventId, Path destination) throws Exception;
    @Override default void close() throws Exception { }
}
