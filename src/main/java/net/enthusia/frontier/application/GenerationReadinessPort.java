package net.enthusia.frontier.application;

import java.util.concurrent.CompletableFuture;
import net.enthusia.frontier.domain.ChunkKey;

/** Hot readiness view used by the movement/generation shield without loading chunks. */
public interface GenerationReadinessPort {
    boolean isReady(ChunkKey key) throws Exception;

    /**
     * Adds an already-observed generated chunk to the hot readiness view without performing
     * persistence. The lifecycle ledger owns durability for this event path.
     *
     * @return true only when the chunk was not already present in the hot readiness view
     */
    boolean observeReady(ChunkKey key);

    /** Completes only after readiness is durably recorded and safe to expose to movement. */
    CompletableFuture<Void> markReady(ChunkKey key);
}
