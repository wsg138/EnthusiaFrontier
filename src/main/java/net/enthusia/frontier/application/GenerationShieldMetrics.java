package net.enthusia.frontier.application;

/** Immutable operational snapshot for /frontier status and staging load tests. */
public record GenerationShieldMetrics(
        int queued,
        int inFlight,
        long started,
        long completed,
        long rejected,
        long deduplicated,
        boolean healthy) {
}
