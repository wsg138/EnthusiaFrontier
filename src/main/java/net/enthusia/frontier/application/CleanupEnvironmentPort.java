package net.enthusia.frontier.application;

import net.enthusia.frontier.domain.ChunkKey;

/** Platform safety checks that must pass immediately before a destructive clear. */
public interface CleanupEnvironmentPort {
    double recentMspt();

    boolean isSafeToClear(String worldName, ChunkKey key, int minimumPlayerDistanceChunks);
}
