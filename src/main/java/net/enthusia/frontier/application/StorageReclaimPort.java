package net.enthusia.frontier.application;

import java.util.List;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;

/** Version-specific world-storage operations isolated from cleanup policy. */
public interface StorageReclaimPort {
    String adapterName();

    boolean supportsPhysicalReclaim();

    void clearChunk(String worldName, ChunkKey key) throws Exception;

    void flushWorld(String worldName, String expectedWorldUuid) throws Exception;

    RegionReclaimResult reclaimEmptyRegion(String worldName, RegionKey region) throws Exception;

    boolean primaryChunkDataPresent(String worldName, ChunkKey key) throws Exception;

    /** Read-only occupancy used by the isolated real-server acceptance harness. */
    List<ChunkKey> occupiedChunks(String worldName, RegionKey region) throws Exception;
}
