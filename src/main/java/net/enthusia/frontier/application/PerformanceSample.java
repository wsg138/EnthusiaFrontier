package net.enthusia.frontier.application;

import java.util.Objects;

/** One low-frequency server-performance observation used for evidence-based tuning. */
public record PerformanceSample(
        long epochMillis,
        double mspt,
        double tpsOneMinute,
        int onlinePlayers,
        double averageViewDistance,
        int maxViewDistance,
        double averageSendViewDistance,
        int maxSendViewDistance,
        double averageSimulationDistance,
        int loadedChunks,
        long generatedChunks,
        double generatedChunksPerSecond,
        int shieldQueued,
        int shieldInFlight,
        double shieldDebtChunks,
        String generationBand,
        String viewDistanceBand,
        int targetViewDistance) {

    public PerformanceSample {
        Objects.requireNonNull(generationBand, "generationBand");
        Objects.requireNonNull(viewDistanceBand, "viewDistanceBand");
        if (epochMillis < 0L || !Double.isFinite(mspt) || mspt < 0.0) {
            throw new IllegalArgumentException("invalid performance sample time/MSPT");
        }
        if (!Double.isFinite(tpsOneMinute) || tpsOneMinute < 0.0) {
            throw new IllegalArgumentException("invalid performance sample TPS");
        }
        if (onlinePlayers < 0 || loadedChunks < 0 || generatedChunks < 0L
                || shieldQueued < 0 || shieldInFlight < 0) {
            throw new IllegalArgumentException("performance sample counters must be non-negative");
        }
        if (!Double.isFinite(generatedChunksPerSecond) || generatedChunksPerSecond < 0.0
                || !Double.isFinite(shieldDebtChunks) || shieldDebtChunks < 0.0) {
            throw new IllegalArgumentException("performance sample rates/debt must be non-negative and finite");
        }
    }

    public boolean generationActive() {
        return generatedChunks > 0L || generatedChunksPerSecond > 0.0 || shieldQueued > 0 || shieldInFlight > 0;
    }
}
