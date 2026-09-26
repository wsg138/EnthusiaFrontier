package net.enthusia.frontier.adapter.paper;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.frontier.application.ChunkGenerationPort;
import net.enthusia.frontier.domain.ChunkKey;
import org.bukkit.Server;
import org.bukkit.World;

/** Paper async, non-urgent generation adapter. Frontier controls admission globally. */
public final class PaperChunkGenerationAdapter implements ChunkGenerationPort {
    private final Server server;

    public PaperChunkGenerationAdapter(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public CompletableFuture<Void> generate(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        final UUID worldId;
        try {
            worldId = UUID.fromString(key.worldUuid());
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        World world = server.getWorld(worldId);
        if (world == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("managed world is not loaded: " + key.worldUuid()));
        }
        return world.getChunkAtAsync(key.x(), key.z(), true, false).thenApply(chunk -> null);
    }
}
