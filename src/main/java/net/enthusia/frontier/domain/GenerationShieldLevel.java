package net.enthusia.frontier.domain;

import java.util.Objects;

/** One MSPT band for Frontier's true whole-server generation shield. */
public record GenerationShieldLevel(
        String name,
        double enterMspt,
        GlobalGenerationLimits limits) {

    public GenerationShieldLevel {
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
