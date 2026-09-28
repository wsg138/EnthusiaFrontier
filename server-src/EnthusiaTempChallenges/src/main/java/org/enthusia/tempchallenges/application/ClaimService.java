package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ClaimResult;

import java.util.function.Consumer;

public final class ClaimService {
    private final OrderedChallengeProcessor processor;
    private final RewardSink rewards;
    private final Consumer<Exception> rewardFailure;

    public ClaimService(OrderedChallengeProcessor processor, RewardSink rewards, Consumer<Exception> rewardFailure) {
        this.processor = processor;
        this.rewards = rewards;
        this.rewardFailure = rewardFailure;
    }

    public ClaimResult handle(ChallengeAttempt attempt) {
        ClaimResult result = processor.process(attempt);
        if (!result.claimed()) return result;
        try {
            rewards.deliver(attempt, result.winner());
        } catch (Exception exception) {
            rewardFailure.accept(exception);
        }
        return result;
    }
}
