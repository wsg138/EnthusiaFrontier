package net.enthusia.frontier.application;

import java.util.Objects;
import net.enthusia.frontier.domain.AdaptiveViewDistancePolicy;
import net.enthusia.frontier.domain.ViewDistanceLevel;

/** Applies view-distance pressure immediately and recovers upward one band at a time. */
public final class AdaptiveViewDistanceService {
    private final AdaptiveViewDistancePolicy policy;
    private final ServerPerformancePort performance;
    private final ViewDistancePort viewDistancePort;
    private final int recoveryStableSamples;
    private volatile ViewDistanceLevel currentLevel;
    private volatile double lastMspt;
    private int recoverySamples;

    public AdaptiveViewDistanceService(
            AdaptiveViewDistancePolicy policy,
            ServerPerformancePort performance,
            ViewDistancePort viewDistancePort,
            int recoveryStableSamples) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.performance = Objects.requireNonNull(performance, "performance");
        this.viewDistancePort = Objects.requireNonNull(viewDistancePort, "viewDistancePort");
        if (recoveryStableSamples < 1) {
            throw new IllegalArgumentException("recoveryStableSamples must be >= 1");
        }
        this.recoveryStableSamples = recoveryStableSamples;
    }

    public ViewDistanceLevel sample() throws Exception {
        double mspt = performance.currentAverageMspt();
        ViewDistanceLevel selected = policy.select(mspt, currentLevel);
        if (currentLevel == null) {
            apply(selected);
        } else {
            int currentIndex = policy.indexOf(currentLevel);
            int selectedIndex = policy.indexOf(selected);
            if (selectedIndex > currentIndex) {
                recoverySamples = 0;
                apply(selected);
            } else if (selectedIndex < currentIndex) {
                recoverySamples++;
                if (recoverySamples >= recoveryStableSamples) {
                    recoverySamples = 0;
                    apply(policy.levels().get(currentIndex - 1));
                }
            } else {
                recoverySamples = 0;
            }
        }
        lastMspt = mspt;
        return currentLevel;
    }

    public ViewDistanceLevel currentLevel() {
        return currentLevel;
    }

    public double lastMspt() {
        return lastMspt;
    }

    public int recoverySamples() {
        return recoverySamples;
    }

    public void restore() {
        viewDistancePort.restore();
    }

    private void apply(ViewDistanceLevel level) {
        viewDistancePort.apply(level.viewDistance());
        currentLevel = level;
    }
}
