package net.enthusia.frontier.domain;

/** Whole-server generation budget enforced by Frontier itself. Zero means pause. */
public record GlobalGenerationLimits(double chunksPerSecond, int maxConcurrent) {
    public GlobalGenerationLimits {
        if (!Double.isFinite(chunksPerSecond) || chunksPerSecond < 0.0) {
            throw new IllegalArgumentException("chunksPerSecond must be non-negative and finite");
        }
        if (maxConcurrent < 0) {
            throw new IllegalArgumentException("maxConcurrent must be >= 0");
        }
    }

    public boolean paused() {
        return chunksPerSecond == 0.0 || maxConcurrent == 0;
    }
}
