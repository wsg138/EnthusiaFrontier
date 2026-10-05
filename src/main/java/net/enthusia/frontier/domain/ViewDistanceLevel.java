package net.enthusia.frontier.domain;

import java.util.Objects;

/** One MSPT band and the effective player view/send distance used in that band. */
public record ViewDistanceLevel(String name, double enterMspt, int viewDistance) {
    public ViewDistanceLevel {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("view-distance level name must not be blank");
        }
        if (!Double.isFinite(enterMspt) || enterMspt < 0.0) {
            throw new IllegalArgumentException("enterMspt must be non-negative and finite");
        }
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("viewDistance must be within 2..32 chunks");
        }
    }
}
