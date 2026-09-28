package net.enthusia.frontier.application;

import java.util.concurrent.CompletionStage;
import net.enthusia.frontier.domain.ChunkKey;

/** Receives an observed generated chunk together with its read-only durable lifecycle boundary. */
@FunctionalInterface
public interface DurableGenerationObserver {
    void observe(ChunkKey key, CompletionStage<Void> durableCommit);
}
