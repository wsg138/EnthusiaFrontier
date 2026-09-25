package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ClaimDecision;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.persistence.ChallengeLedger;

import java.sql.SQLException;

public final class OrderedChallengeProcessor {
    private final ChallengeLedger ledger;

    public OrderedChallengeProcessor(ChallengeLedger ledger) {
        this.ledger = ledger;
    }

    public ClaimResult process(ChallengeAttempt attempt) {
        try {
            return ledger.attempt(attempt);
        } catch (SQLException exception) {
            return ClaimResult.of(ClaimDecision.PERSISTENCE_FAILED, null, exception.getMessage());
        }
    }
}
