package org.enthusia.tempchallenges.application;

import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.SignalKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChallengeRouter {
    private final Map<SignalKey, List<ChallengeDefinition>> routes = new HashMap<>();

    public ChallengeRouter(Collection<ChallengeDefinition> definitions) {
        for (ChallengeDefinition definition : definitions) {
            for (SignalKey signal : definition.signals()) {
                routes.computeIfAbsent(signal, ignored -> new ArrayList<>()).add(definition);
            }
        }
    }

    public List<ChallengeDefinition> route(SignalKey signal) {
        return List.copyOf(routes.getOrDefault(signal, List.of()));
    }
}
