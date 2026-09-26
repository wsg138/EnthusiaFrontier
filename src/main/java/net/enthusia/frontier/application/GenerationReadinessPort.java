package net.enthusia.frontier.application;

import net.enthusia.frontier.domain.ChunkKey;

/** Hot readiness view used by the movement/generation shield without loading chunks. */
public interface GenerationReadinessPort {
    boolean isReady(ChunkKey key) throws Exception;

    void markReady(ChunkKey key) throws Exception;
}
