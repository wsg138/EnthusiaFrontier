package net.enthusia.frontier.application;

import java.time.Instant;
import java.util.Objects;
import net.enthusia.frontier.domain.ChunkKey;

/** One durable ledger row that is old enough to be considered for cleanup. */
public record CleanupCandidate(ChunkKey key, Instant generatedAt) {
    public CleanupCandidate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(generatedAt, "generatedAt");
    }
}
