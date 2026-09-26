package net.enthusia.frontier.application;

import java.util.concurrent.CompletableFuture;
import net.enthusia.frontier.domain.ChunkKey;

/** Outbound port that starts one asynchronous chunk-generation job. */
@FunctionalInterface
public interface ChunkGenerationPort {
    CompletableFuture<Void> generate(ChunkKey key);
}
