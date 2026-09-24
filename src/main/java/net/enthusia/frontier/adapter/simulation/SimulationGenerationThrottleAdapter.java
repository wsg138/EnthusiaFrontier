package net.enthusia.frontier.adapter.simulation;

import net.enthusia.frontier.application.GenerationThrottlePort;
import net.enthusia.frontier.domain.GenerationLimits;

/**
 * Sentinel/MockBukkit-only adapter. It records selected limits but does not claim
 * to model Paper's real chunk scheduler. Real Paper/Leaf must use the Paper adapter.
 */
public final class SimulationGenerationThrottleAdapter implements GenerationThrottlePort {
    private GenerationLimits currentLimits;

    @Override
    public synchronized void apply(GenerationLimits limits) {
        currentLimits = limits;
    }

    @Override
    public synchronized void restore() {
        currentLimits = null;
    }

    public synchronized GenerationLimits currentLimits() {
        return currentLimits;
    }
}
