package net.enthusia.frontier.application;

import java.util.Objects;
import net.enthusia.frontier.domain.AdaptiveThrottlePolicy;
import net.enthusia.frontier.domain.ThrottleLevel;

/** Samples MSPT and applies server generation limits only when the policy band changes. */
public final class AdaptiveThrottleService {
    private final AdaptiveThrottlePolicy policy;
    private final ServerPerformancePort performance;
    private final GenerationThrottlePort throttlePort;
    private volatile ThrottleLevel currentLevel;
    private volatile double lastMspt;

    public AdaptiveThrottleService(
            AdaptiveThrottlePolicy policy,
            ServerPerformancePort performance,
            GenerationThrottlePort throttlePort) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.performance = Objects.requireNonNull(performance, "performance");
        this.throttlePort = Objects.requireNonNull(throttlePort, "throttlePort");
    }

    public ThrottleLevel sample() throws Exception {
        double mspt = performance.currentAverageMspt();
        ThrottleLevel selected = policy.select(mspt, currentLevel);
        if (!selected.equals(currentLevel)) {
            throttlePort.apply(selected.limits());
            currentLevel = selected;
        }
        lastMspt = mspt;
        return selected;
    }

    public ThrottleLevel currentLevel() {
        return currentLevel;
    }

    public double lastMspt() {
        return lastMspt;
    }

    public void restore() throws Exception {
        throttlePort.restore();
    }
}
