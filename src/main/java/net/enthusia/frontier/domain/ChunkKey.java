package net.enthusia.frontier.domain;

import java.util.Objects;

/** Immutable identity for a chunk in one specific world UUID. */
public record ChunkKey(String worldUuid, int x, int z) {
    public ChunkKey {
        Objects.requireNonNull(worldUuid, "worldUuid");
        if (worldUuid.isBlank()) {
            throw new IllegalArgumentException("worldUuid must not be blank");
        }
    }

    public ChunkKey offset(int deltaX, int deltaZ) {
        return new ChunkKey(worldUuid, Math.addExact(x, deltaX), Math.addExact(z, deltaZ));
    }
}
