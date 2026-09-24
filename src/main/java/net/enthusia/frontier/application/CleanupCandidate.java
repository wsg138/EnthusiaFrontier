package net.enthusia.frontier.application;

import java.time.Instant;
import java.util.Objects;
import net.enthusia.frontier.domain.ChunkKey;

/** One durable ledger row that is old enough to be considered for cleanup. */
public record CleanupCandidate(ChunkKey key, Instant generatedAt, Instant reclaimIntentAt) {
    public CleanupCandidate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(generatedAt, "generatedAt");
    }

    public CleanupCandidate(ChunkKey key, Instant generatedAt) {
        this(key, generatedAt, null);
    }

    /** True only when destructive intent was committed before the candidate left SQLite. */
    public boolean hasReclaimIntent() {
        return reclaimIntentAt != null;
    }
}
