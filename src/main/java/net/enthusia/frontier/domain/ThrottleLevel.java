package net.enthusia.frontier.domain;

import java.util.Objects;

/** One monotonic MSPT throttle tier. */
public record ThrottleLevel(
        String name,
        double enterMspt,
        GenerationLimits limits) {

    public ThrottleLevel {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(limits, "limits");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!Double.isFinite(enterMspt) || enterMspt < 0.0) {
            throw new IllegalArgumentException("enterMspt must be a non-negative finite value");
        }
    }
}
