package net.enthusia.frontier.config;

/** Validated settings for bounded frontier cleanup and physical region reclamation. */
public record CleanupSettings(
        boolean enabled,
        boolean dryRun,
        long untouchedRetentionDays,
        long scanPeriodTicks,
        int candidateBatchSize,
        int maxChunksPerTick,
        int minPlayerDistanceChunks,
        double maxMspt,
        boolean auditLog,
        boolean physicalReclaim,
        boolean requireSupportedAdapter) {

    private static final long MAX_RETENTION_DAYS = 3650L;

    public CleanupSettings {
        if (untouchedRetentionDays < 0L || untouchedRetentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("cleanup.untouched-retention-days must be within 0..3650");
        }
        if (scanPeriodTicks < 20L) {
            throw new IllegalArgumentException("cleanup.scan-period-ticks must be >= 20");
        }
        if (candidateBatchSize < 1 || candidateBatchSize > 4096) {
            throw new IllegalArgumentException("cleanup.candidate-batch-size must be within 1..4096");
        }
        if (maxChunksPerTick < 1 || maxChunksPerTick > 64) {
            throw new IllegalArgumentException("cleanup.max-chunks-per-tick must be within 1..64");
        }
        if (minPlayerDistanceChunks < 0 || minPlayerDistanceChunks > 128) {
            throw new IllegalArgumentException("cleanup.min-player-distance-chunks must be within 0..128");
        }
        if (!Double.isFinite(maxMspt) || maxMspt <= 0.0 || maxMspt > 50.0) {
            throw new IllegalArgumentException("cleanup.max-mspt must be within (0, 50]");
        }
        if (!enabled && !dryRun) {
            throw new IllegalArgumentException("cleanup.dry-run may be false only when cleanup.enabled=true");
        }
    }
}
