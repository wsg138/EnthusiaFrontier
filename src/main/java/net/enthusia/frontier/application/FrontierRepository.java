package net.enthusia.frontier.application;

import java.time.Instant;
import java.util.List;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;

/** Outbound port for the durable frontier ledger. */
public interface FrontierRepository {
    void initialize() throws Exception;

    void applyBatch(List<FrontierMutation> mutations) throws Exception;

    List<CleanupCandidate> findCleanupCandidates(String worldUuid, Instant cutoff, int limit) throws Exception;

    /**
     * Atomically recovers existing destructive intents and reserves additional eligible candidates.
     * Returned candidates must carry a durable reclaim intent before leaving the repository.
     */
    default List<CleanupCandidate> reserveCleanupCandidates(
            String worldUuid, Instant cutoff, int limit, Instant reservedAt) throws Exception {
        throw new UnsupportedOperationException("durable cleanup reservation is not implemented");
    }

    List<RegionKey> findDeletedRegions(String worldUuid, int limit) throws Exception;

    boolean isProtected(ChunkKey key) throws Exception;

    boolean isDeleted(ChunkKey key) throws Exception;

    FrontierStats stats() throws Exception;

    void close() throws Exception;
}
