package net.enthusia.frontier.domain;

/** Paper per-player generation limits selected by the adaptive throttle policy. */
public record GenerationLimits(double maxGenerateRate, int maxConcurrentGenerations) {
    public GenerationLimits {
        if (!Double.isFinite(maxGenerateRate) || (maxGenerateRate <= 0.0 && maxGenerateRate != -1.0)) {
            throw new IllegalArgumentException("maxGenerateRate must be -1 or a positive finite value");
        }
        if (maxConcurrentGenerations < -1) {
            throw new IllegalArgumentException("maxConcurrentGenerations must be >= -1");
        }
    }
}
