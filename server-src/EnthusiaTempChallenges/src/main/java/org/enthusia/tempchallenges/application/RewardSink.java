package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.WinnerRecord;

@FunctionalInterface
public interface RewardSink {
    void deliver(ChallengeAttempt attempt, WinnerRecord winner) throws Exception;
}
