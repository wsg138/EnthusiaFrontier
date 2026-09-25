package org.enthusia.tempchallenges.persistence;

import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.ClaimDecision;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.domain.Eligibility;
import org.enthusia.tempchallenges.domain.EventState;
import org.enthusia.tempchallenges.domain.SignalKey;
import org.enthusia.tempchallenges.domain.SignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcChallengeLedgerTest {
    @TempDir Path temp;

    @Test void firstClaimantWinsAndSecondLoses() throws Exception {
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(temp.resolve("first.sqlite"))) {
            ClaimResult first = ledger.attempt(attempt("a", UUID.randomUUID(), "one", Eligibility.allow(), EventState.ACTIVE, false));
            ClaimResult second = ledger.attempt(attempt("b", UUID.randomUUID(), "two", Eligibility.allow(), EventState.ACTIVE, false));
            assertEquals(ClaimDecision.CLAIMED, first.decision());
            assertEquals(ClaimDecision.ALREADY_CLAIMED, second.decision());
            assertEquals(first.winner().uuid(), ledger.winner("event", "first_diamonds").orElseThrow().uuid());
        }
    }

    @Test void duplicateReplayIsIdempotent() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(temp.resolve("duplicate.sqlite"))) {
            ChallengeAttempt attempt = attempt("same-signal", uuid, "one", Eligibility.allow(), EventState.ACTIVE, false);
            assertEquals(ClaimDecision.CLAIMED, ledger.attempt(attempt).decision());
            assertEquals(ClaimDecision.DUPLICATE_SIGNAL, ledger.attempt(attempt).decision());
            assertEquals(uuid, ledger.winner("event", "first_diamonds").orElseThrow().uuid());
        }
    }

    @Test void restartRecoversExistingWinner() throws Exception {
        Path db = temp.resolve("restart.sqlite");
        UUID winner = UUID.randomUUID();
        try (JdbcChallengeLedger first = new JdbcChallengeLedger(db)) {
            assertTrue(first.attempt(attempt("before-restart", winner, "one", Eligibility.allow(), EventState.ACTIVE, false)).claimed());
        }
        try (JdbcChallengeLedger restarted = new JdbcChallengeLedger(db)) {
            assertEquals(winner, restarted.winner("event", "first_diamonds").orElseThrow().uuid());
            assertEquals(ClaimDecision.ALREADY_CLAIMED,
                    restarted.attempt(attempt("after-restart", UUID.randomUUID(), "two", Eligibility.allow(), EventState.ACTIVE, false)).decision());
        }
    }

    @Test void simultaneousRaceProducesExactlyOneWinner() throws Exception {
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(temp.resolve("race.sqlite"))) {
            int racers = 12;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            CountDownLatch start = new CountDownLatch(1);
            try {
                @SuppressWarnings("unchecked") Future<ClaimResult>[] futures = new Future[racers];
                for (int i = 0; i < racers; i++) {
                    final int index = i;
                    futures[i] = pool.submit(() -> {
                        start.await();
                        return ledger.attempt(attempt("race-" + index, UUID.randomUUID(), "p" + index,
                                Eligibility.allow(), EventState.ACTIVE, false));
                    });
                }
                start.countDown();
                int claimed = 0;
                int already = 0;
                for (Future<ClaimResult> future : futures) {
                    ClaimDecision decision = future.get(20, TimeUnit.SECONDS).decision();
                    if (decision == ClaimDecision.CLAIMED) claimed++;
                    if (decision == ClaimDecision.ALREADY_CLAIMED) already++;
                }
                assertEquals(1, claimed);
                assertEquals(racers - 1, already);
                assertTrue(ledger.winner("event", "first_diamonds").isPresent());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void lockedElytraCannotClaimEvenWithSignal() throws Exception {
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(temp.resolve("locked.sqlite"))) {
            ChallengeAttempt locked = attempt("elytra-signal", UUID.randomUUID(), "op-gave-item",
                    Eligibility.allow(), EventState.ACTIVE, true);
            assertEquals(ClaimDecision.LOCKED, ledger.attempt(locked).decision());
            assertTrue(ledger.winner("event", "first_diamonds").isEmpty());
        }
    }

    @Test void ineligibleAndClosedEventsCannotWin() throws Exception {
        try (JdbcChallengeLedger ledger = new JdbcChallengeLedger(temp.resolve("eligibility.sqlite"))) {
            assertEquals(ClaimDecision.INELIGIBLE,
                    ledger.attempt(attempt("creative", UUID.randomUUID(), "creative", Eligibility.deny("creative-excluded"), EventState.ACTIVE, false)).decision());
            assertEquals(ClaimDecision.EVENT_NOT_ACTIVE,
                    ledger.attempt(attempt("closed", UUID.randomUUID(), "closed", Eligibility.allow(), EventState.CLOSED, false)).decision());
            assertTrue(ledger.winner("event", "first_diamonds").isEmpty());
        }
    }

    private ChallengeAttempt attempt(String signalId, UUID uuid, String name, Eligibility eligibility, EventState state, boolean locked) {
        ChallengeDefinition challenge = new ChallengeDefinition("first_diamonds", "Diamond Pioneer", "obtain Diamonds",
                "enthusia.frontier.first.diamonds", "frontier_first_diamonds", "first_diamonds", 250,
                locked, locked ? "server policy" : "", Set.of(new SignalKey(SignalType.ITEM_ACQUIRED, "DIAMOND")));
        return new ChallengeAttempt("event", challenge, new ActorIdentity(uuid, name, ActorIdentity.Platform.JAVA),
                signalId, new SignalKey(SignalType.ITEM_ACQUIRED, "DIAMOND"), "test", Instant.now(), eligibility, state);
    }
}
