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
    private final int recoveryFastStableSamples;
    private final double recoveryFastHeadroomMspt;
    private ViewDistanceLevel currentLevel;
    private double lastMspt;
    private int recoverySamples;

    public AdaptiveViewDistanceService(
            AdaptiveViewDistancePolicy policy,
            ServerPerformancePort performance,
            ViewDistancePort viewDistancePort,
            int recoveryStableSamples) {
        this(policy, performance, viewDistancePort, recoveryStableSamples,
                recoveryStableSamples, 0.0);
    }

    public AdaptiveViewDistanceService(
            AdaptiveViewDistancePolicy policy,
            ServerPerformancePort performance,
            ViewDistancePort viewDistancePort,
            int recoveryStableSamples,
            int recoveryFastStableSamples,
            double recoveryFastHeadroomMspt) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.performance = Objects.requireNonNull(performance, "performance");
        this.viewDistancePort = Objects.requireNonNull(viewDistancePort, "viewDistancePort");
        if (recoveryStableSamples < 1) {
            throw new IllegalArgumentException("recoveryStableSamples must be >= 1");
        }
        if (recoveryFastStableSamples < 1 || recoveryFastStableSamples > recoveryStableSamples) {
            throw new IllegalArgumentException("recoveryFastStableSamples must be within 1..recoveryStableSamples");
        }
        if (!Double.isFinite(recoveryFastHeadroomMspt) || recoveryFastHeadroomMspt < 0.0) {
            throw new IllegalArgumentException("recoveryFastHeadroomMspt must be non-negative and finite");
        }
        this.recoveryStableSamples = recoveryStableSamples;
        this.recoveryFastStableSamples = recoveryFastStableSamples;
        this.recoveryFastHeadroomMspt = recoveryFastHeadroomMspt;
    }

    public synchronized ViewDistanceLevel sample() throws Exception {
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
                if (recoverySamples >= requiredRecoverySamples(mspt)) {
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

    public synchronized ViewDistanceLevel currentLevel() {
        return currentLevel;
    }

    public synchronized double lastMspt() {
        return lastMspt;
    }

    public synchronized int recoverySamples() {
        return recoverySamples;
    }

    /** Diagnostic snapshot for the next one-band recovery step, or null at maximum distance. */
    public synchronized RecoveryStatus recoveryStatus() {
        if (currentLevel == null) {
            return null;
        }
        int currentIndex = policy.indexOf(currentLevel);
        if (currentIndex <= 0) {
            return null;
        }
        double threshold = policy.recoveryThresholdFor(currentLevel);
        boolean eligible = policy.indexOf(policy.select(lastMspt, currentLevel)) < currentIndex;
        boolean fast = eligible && threshold - lastMspt >= recoveryFastHeadroomMspt;
        int required = fast ? recoveryFastStableSamples : recoveryStableSamples;
        return new RecoveryStatus(policy.levels().get(currentIndex - 1),
                threshold, eligible, recoverySamples, required, fast);
    }

    public record RecoveryStatus(
            ViewDistanceLevel nextLevel,
            double belowMspt,
            boolean eligible,
            int stableSamples,
            int requiredSamples,
            boolean fast) {
    }

    private int requiredRecoverySamples(double mspt) {
        return policy.recoveryThresholdFor(currentLevel) - mspt >= recoveryFastHeadroomMspt
                ? recoveryFastStableSamples
                : recoveryStableSamples;
    }

    public synchronized void restore() {
        viewDistancePort.restore();
    }

    private void apply(ViewDistanceLevel level) {
        viewDistancePort.apply(level.viewDistance());
        currentLevel = level;
    }
}
