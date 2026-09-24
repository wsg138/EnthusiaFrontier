package net.enthusia.frontier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;

/** Application use cases for generation observation and monotonic player-activity protection. */
public final class FrontierTrackingService {
    private final Map<String, CoreBoundaryPolicy> worldPolicies;
    private final int protectionRadiusChunks;
    private final MutationJournal journal;
    private final Clock clock;
    private final Set<ChunkKey> runtimeProtected = ConcurrentHashMap.newKeySet();

    public FrontierTrackingService(
            Map<String, CoreBoundaryPolicy> worldPolicies,
            int protectionRadiusChunks,
            MutationJournal journal,
            Clock clock) {
        this.worldPolicies = Map.copyOf(Objects.requireNonNull(worldPolicies, "worldPolicies"));
        if (protectionRadiusChunks < 0 || protectionRadiusChunks > 32) {
            throw new IllegalArgumentException("protectionRadiusChunks must be within 0..32");
        }
        this.protectionRadiusChunks = protectionRadiusChunks;
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean recordGenerated(String worldName, UUID worldUuid, int chunkX, int chunkZ) {
        CoreBoundaryPolicy policy = worldPolicies.get(worldName);
        if (policy == null) {
            return false;
        }
        ChunkKey key = key(worldUuid, chunkX, chunkZ);
        if (!policy.isManaged(key)) {
            return false;
        }
        return journal.submit(new FrontierMutation.Generated(key, Instant.now(clock)));
    }

    public int recordActivity(
            String worldName,
            UUID worldUuid,
            int chunkX,
            int chunkZ,
            ActivityKind kind) {
        CoreBoundaryPolicy policy = worldPolicies.get(worldName);
        if (policy == null) {
            return 0;
        }
        Instant now = Instant.now(clock);
        int accepted = 0;
        ChunkKey origin = key(worldUuid, chunkX, chunkZ);
        for (int deltaX = -protectionRadiusChunks; deltaX <= protectionRadiusChunks; deltaX++) {
            for (int deltaZ = -protectionRadiusChunks; deltaZ <= protectionRadiusChunks; deltaZ++) {
                ChunkKey key = origin.offset(deltaX, deltaZ);
                if (!policy.isManaged(key)) {
                    continue;
                }
                // Protect in memory before the async ledger write. Cleanup consults this
                // set immediately before deletion, closing the candidate-scan race window.
                runtimeProtected.add(key);
                if (journal.submit(new FrontierMutation.Protected(key, now, kind))) {
                    accepted++;
                }
            }
        }
        return accepted;
    }

    public boolean isProtectedInMemory(ChunkKey key) {
        return runtimeProtected.contains(Objects.requireNonNull(key, "key"));
    }

    public Map<String, CoreBoundaryPolicy> worldPolicies() {
        return worldPolicies;
    }

    private static ChunkKey key(UUID worldUuid, int chunkX, int chunkZ) {
        return new ChunkKey(Objects.requireNonNull(worldUuid, "worldUuid").toString(), chunkX, chunkZ);
    }
}
