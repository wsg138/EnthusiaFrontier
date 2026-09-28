package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.ClaimDecision;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.domain.DragonContribution;
import org.enthusia.tempchallenges.domain.Eligibility;
import org.enthusia.tempchallenges.domain.EventState;
import org.enthusia.tempchallenges.domain.SignalKey;
import org.enthusia.tempchallenges.domain.SignalType;
import org.enthusia.tempchallenges.domain.WinnerRecord;
import org.enthusia.tempchallenges.persistence.ChallengeLedger;
import org.enthusia.tempchallenges.persistence.JdbcChallengeLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClaimServiceTest {
    @TempDir Path temp;

    @Test void persistenceFailureNeverFiresRewardPath() {
        AtomicBoolean rewarded = new AtomicBoolean();
        ClaimService service = new ClaimService(new OrderedChallengeProcessor(new FailingLedger()),
                (attempt, winner) -> rewarded.set(true), ignored -> { });
        ClaimResult result = service.handle(attempt("fail"));
        assertEquals(ClaimDecision.PERSISTENCE_FAILED, result.decision());
        assertFalse(rewarded.get());
    }

    @Test void rewardFailureDoesNotEraseDurableWinner() throws Exception {
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(temp.resolve("reward.sqlite"))) {
            AtomicInteger failures = new AtomicInteger();
            ClaimService service = new ClaimService(new OrderedChallengeProcessor(ledger),
                    (attempt, winner) -> { throw new IllegalStateException("projection failed"); },
                    ignored -> failures.incrementAndGet());
            ClaimResult result = service.handle(attempt("reward-failure"));
            assertEquals(ClaimDecision.CLAIMED, result.decision());
            assertEquals(1, failures.get());
            assertEquals(result.winner().uuid(), ledger.winner("event", "first_diamonds").orElseThrow().uuid());
        }
    }

    private ChallengeAttempt attempt(String signalId) {
        ChallengeDefinition challenge = new ChallengeDefinition("first_diamonds", "Diamond Pioneer", "obtain Diamonds",
                "enthusia.frontier.first.diamonds", "frontier_first_diamonds", "first_diamonds", 250,
                false, "", Set.of(new SignalKey(SignalType.ITEM_ACQUIRED, "DIAMOND")));
        return new ChallengeAttempt("event", challenge,
                new ActorIdentity(UUID.randomUUID(), "tester", ActorIdentity.Platform.JAVA), signalId,
                new SignalKey(SignalType.ITEM_ACQUIRED, "DIAMOND"), "test", Instant.now(), Eligibility.allow(), EventState.ACTIVE);
    }

    private static final class FailingLedger implements ChallengeLedger {
        @Override public ClaimResult attempt(ChallengeAttempt attempt) throws SQLException { throw new SQLException("disk unavailable"); }
        @Override public Optional<WinnerRecord> winner(String eventId, String challengeId) { return Optional.empty(); }
        @Override public Map<String, WinnerRecord> winners(String eventId) { return Map.of(); }
        @Override public boolean revoke(String eventId, String challengeId, UUID expectedWinner, String actor, String reason) { return false; }
        @Override public void markReward(String eventId, String challengeId, String kind, String state, String error) { }
        @Override public Map<String, String> pendingRewards(String eventId, String challengeId) { return Map.of(); }
        @Override public void saveDragonSession(String eventId, UUID dragonUuid, Collection<DragonContribution> contributions, UUID finalKiller) { }
        @Override public Path exportCsv(String eventId, Path destination) { return destination; }
    }
}
