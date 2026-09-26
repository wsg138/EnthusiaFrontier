package net.enthusia.frontier.application;

import java.util.concurrent.CompletableFuture;
import net.enthusia.frontier.domain.ChunkKey;

/** Hot readiness view used by the movement/generation shield without loading chunks. */
public interface GenerationReadinessPort {
    boolean isReady(ChunkKey key) throws Exception;

    /** Completes only after readiness is durably recorded and safe to expose to movement. */
    CompletableFuture<Void> markReady(ChunkKey key);
}
