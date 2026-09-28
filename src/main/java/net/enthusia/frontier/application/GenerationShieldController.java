package net.enthusia.frontier.application;

import java.util.Objects;
import net.enthusia.frontier.domain.GenerationShieldLevel;
import net.enthusia.frontier.domain.GenerationShieldPolicy;

/** Samples MSPT and changes the one whole-server generation budget. */
public final class GenerationShieldController {
    private final GenerationShieldPolicy policy;
    private final ServerPerformancePort performance;
    private final GenerationShieldService shield;
    private GenerationShieldLevel currentLevel;
    private double lastMspt;

    public GenerationShieldController(
            GenerationShieldPolicy policy,
            ServerPerformancePort performance,
            GenerationShieldService shield) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.performance = Objects.requireNonNull(performance, "performance");
        this.shield = Objects.requireNonNull(shield, "shield");
    }

    public GenerationShieldLevel sample() {
        double mspt = performance.currentAverageMspt();
        GenerationShieldLevel selected = policy.select(mspt, currentLevel);
        if (!selected.equals(currentLevel)) {
            shield.setLimits(selected.limits());
            currentLevel = selected;
        }
        lastMspt = mspt;
        return selected;
    }

    public GenerationShieldLevel currentLevel() {
        return currentLevel;
    }

    public double lastMspt() {
        return lastMspt;
    }
}
