package net.enthusia.frontier.application;

import java.time.Instant;
import java.util.Objects;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;

/** Immutable mutations written to the Frontier ledger by the asynchronous journal. */
public sealed interface FrontierMutation permits FrontierMutation.Generated, FrontierMutation.Protected {
    ChunkKey key();

    Instant observedAt();

    record Generated(ChunkKey key, Instant observedAt) implements FrontierMutation {
        public Generated {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    record Protected(ChunkKey key, Instant observedAt, ActivityKind kind) implements FrontierMutation {
        public Protected {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
