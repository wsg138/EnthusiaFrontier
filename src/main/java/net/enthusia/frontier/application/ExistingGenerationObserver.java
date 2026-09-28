package net.enthusia.frontier.application;

import net.enthusia.frontier.domain.ChunkKey;

/** Receives an already-existing managed chunk that is safe to expose as hot readiness only. */
@FunctionalInterface
public interface ExistingGenerationObserver {
    boolean observe(ChunkKey key);
}
