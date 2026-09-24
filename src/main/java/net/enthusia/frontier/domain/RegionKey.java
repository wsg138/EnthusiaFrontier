package net.enthusia.frontier.domain;

import java.util.Objects;

/** Immutable identity for one 32x32 Anvil region in a specific world UUID. */
public record RegionKey(String worldUuid, int x, int z) {
    private static final int REGION_SHIFT = 5;

    public RegionKey {
        Objects.requireNonNull(worldUuid, "worldUuid");
        if (worldUuid.isBlank()) {
            throw new IllegalArgumentException("worldUuid must not be blank");
        }
    }

    public static RegionKey fromChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return new RegionKey(chunk.worldUuid(), chunk.x() >> REGION_SHIFT, chunk.z() >> REGION_SHIFT);
    }
}
