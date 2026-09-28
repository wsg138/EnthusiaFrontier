package net.enthusia.frontier.application;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.enthusia.frontier.domain.ChunkKey;

/**
 * Builds generated safety squares around movement destinations without rescanning
 * the square on every movement packet. Pending centers are refreshed separately by
 * a scheduler while movement checks remain O(1).
 */
public final class GenerationBufferCoordinator {
    private final GenerationShieldService shield;
    private final GenerationReadinessPort readiness;
    private final Map<String, PendingBuffer> byRequester = new LinkedHashMap<>();
    private final Map<String, BufferCenter> prewarmByRequester = new LinkedHashMap<>();

    public GenerationBufferCoordinator(
            GenerationShieldService shield,
            GenerationReadinessPort readiness) {
        this.shield = Objects.requireNonNull(shield, "shield");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    public synchronized GenerationBufferStatus prepare(
            String requesterId,
            String worldUuid,
            int centerX,
            int centerZ,
            int radius) {
        validateRequest(requesterId, worldUuid, radius);

        BufferCenter center = new BufferCenter(worldUuid, centerX, centerZ, radius);
        PendingBuffer existing = byRequester.get(requesterId);
        if (existing != null && existing.center().equals(center)) {
            return existing.missing().isEmpty()
                    ? GenerationBufferStatus.READY
                    : GenerationBufferStatus.PENDING;
        }

        PendingBuffer pending = new PendingBuffer(center, new LinkedHashSet<>(), new LinkedHashSet<>());
        byRequester.put(requesterId, pending);
        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                ChunkKey key = new ChunkKey(worldUuid, centerX + deltaX, centerZ + deltaZ);
                if (!submitOrResolve(requesterId, pending, key)) {
                    // Never retain a partially evaluated buffer. A later recovery must
                    // revalidate the complete square instead of trusting partial state.
                    byRequester.remove(requesterId);
                    return GenerationBufferStatus.FAIL_CLOSED;
                }
            }
        }
        if (pending.missing().isEmpty()) {
            return GenerationBufferStatus.READY;
        }
        return GenerationBufferStatus.PENDING;
    }

    /**
     * Opportunistically submits only the newly exposed strip for an adjacent movement
     * prediction. The current buffer must already be READY before callers use this path,
     * so rescanning the overlapping square would add no safety and can cost hundreds of
     * hot-path readiness checks when a player changes direction repeatedly.
     *
     * <p>If capacity or health prevents the complete strip from being submitted, the
     * prediction is not cached. A later movement packet can retry after capacity drains.
     */
    public synchronized void prewarm(
            String requesterId,
            String worldUuid,
            int currentCenterX,
            int currentCenterZ,
            int nextCenterX,
            int nextCenterZ,
            int radius) {
        validateRequest(requesterId, worldUuid, radius);
        int stepX = nextCenterX - currentCenterX;
        int stepZ = nextCenterZ - currentCenterZ;
        if (Math.abs(stepX) > 1 || Math.abs(stepZ) > 1 || (stepX == 0 && stepZ == 0)) {
            throw new IllegalArgumentException("prewarm destination must be an adjacent chunk");
        }

        BufferCenter next = new BufferCenter(worldUuid, nextCenterX, nextCenterZ, radius);
        if (next.equals(prewarmByRequester.get(requesterId))) {
            return;
        }

        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                int chunkX = nextCenterX + deltaX;
                int chunkZ = nextCenterZ + deltaZ;
                if (insideSquare(chunkX, chunkZ, currentCenterX, currentCenterZ, radius)) {
                    continue;
                }
                ChunkKey key = new ChunkKey(worldUuid, chunkX, chunkZ);
                GenerationAdmission admission = shield.request(requesterId, key);
                if (admission == GenerationAdmission.REJECTED_CAPACITY
                        || admission == GenerationAdmission.REJECTED_UNHEALTHY
                        || admission == GenerationAdmission.REJECTED_STOPPED) {
                    prewarmByRequester.remove(requesterId);
                    return;
                }
            }
        }
        prewarmByRequester.put(requesterId, next);
    }

    /** Charges observed generation immediately but exposes readiness only after lifecycle durability. */
    public boolean observeGenerated(ChunkKey key, CompletionStage<Void> durableCommit) {
        return shield.observeGenerated(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(durableCommit, "durableCommit"));
    }

    /** Refreshes pending readiness away from movement events with a bounded check budget. */
    public synchronized void refresh(int maxChecks) {
        if (maxChecks < 1) {
            throw new IllegalArgumentException("maxChecks must be >= 1");
        }
        int checked = 0;
        Iterator<Map.Entry<String, PendingBuffer>> buffers = byRequester.entrySet().iterator();
        while (buffers.hasNext() && checked < maxChecks) {
            Map.Entry<String, PendingBuffer> entry = buffers.next();
            PendingBuffer pending = entry.getValue();
            Iterator<ChunkKey> iterator = pending.missing().iterator();
            while (iterator.hasNext() && checked < maxChecks) {
                ChunkKey key = iterator.next();
                checked++;
                try {
                    if (readiness.isReady(key)) {
                        iterator.remove();
                        pending.unsubmitted().remove(key);
                    }
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalStateException("generation readiness refresh failed for " + key, exception);
                }
            }

            if (!pending.unsubmitted().isEmpty()) {
                Iterator<ChunkKey> retry = pending.unsubmitted().iterator();
                while (retry.hasNext() && checked < maxChecks) {
                    ChunkKey key = retry.next();
                    checked++;
                    GenerationAdmission admission = shield.request(entry.getKey(), key);
                    if (admission == GenerationAdmission.READY || admission == GenerationAdmission.CORE_BYPASS) {
                        pending.missing().remove(key);
                        retry.remove();
                    } else if (admission == GenerationAdmission.QUEUED
                            || admission == GenerationAdmission.DEDUPLICATED) {
                        retry.remove();
                    } else if (admission == GenerationAdmission.REJECTED_UNHEALTHY
                            || admission == GenerationAdmission.REJECTED_STOPPED) {
                        return;
                    }
                }
            }

            if (pending.missing().isEmpty()) {
                buffers.remove();
            }
        }
    }

    public synchronized void removeRequester(String requesterId) {
        String requester = Objects.requireNonNull(requesterId, "requesterId");
        byRequester.remove(requester);
        prewarmByRequester.remove(requester);
    }

    public synchronized int pendingBuffers() {
        int count = 0;
        for (PendingBuffer pending : byRequester.values()) {
            if (!pending.missing().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public synchronized int pendingChunks(String requesterId) {
        PendingBuffer pending = byRequester.get(Objects.requireNonNull(requesterId, "requesterId"));
        return pending == null ? 0 : pending.missing().size();
    }

    public boolean generationPaused() {
        return shield.limits().paused();
    }

    private boolean submitOrResolve(String requesterId, PendingBuffer pending, ChunkKey key) {
        GenerationAdmission admission = shield.request(requesterId, key);
        return switch (admission) {
            case READY, CORE_BYPASS -> true;
            case QUEUED, DEDUPLICATED -> {
                pending.missing().add(key);
                yield true;
            }
            case REJECTED_CAPACITY -> {
                pending.missing().add(key);
                pending.unsubmitted().add(key);
                yield true;
            }
            case REJECTED_UNHEALTHY, REJECTED_STOPPED -> false;
        };
    }

    private static void validateRequest(String requesterId, String worldUuid, int radius) {
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(worldUuid, "worldUuid");
        if (requesterId.isBlank() || worldUuid.isBlank()) {
            throw new IllegalArgumentException("requesterId and worldUuid must not be blank");
        }
        if (radius < 0 || radius > 40) {
            throw new IllegalArgumentException("buffer radius must be within 0..40 chunks");
        }
    }

    private static boolean insideSquare(int x, int z, int centerX, int centerZ, int radius) {
        return Math.abs((long) x - centerX) <= radius && Math.abs((long) z - centerZ) <= radius;
    }

    private record BufferCenter(String worldUuid, int x, int z, int radius) {
    }

    private record PendingBuffer(
            BufferCenter center,
            Set<ChunkKey> missing,
            Set<ChunkKey> unsubmitted) {
    }
}
