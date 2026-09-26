package net.enthusia.frontier.application;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.enthusia.frontier.domain.ChunkKey;

/**
 * Builds a generated safety square around movement destinations without rescanning
 * the square on every movement packet. Pending centers are refreshed separately by
 * a scheduler while movement checks remain O(1).
 */
public final class GenerationBufferCoordinator {
    private final GenerationShieldService shield;
    private final GenerationReadinessPort readiness;
    private final Map<String, PendingBuffer> byRequester = new LinkedHashMap<>();

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
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(worldUuid, "worldUuid");
        if (requesterId.isBlank() || worldUuid.isBlank()) {
            throw new IllegalArgumentException("requesterId and worldUuid must not be blank");
        }
        if (radius < 0 || radius > 40) {
            throw new IllegalArgumentException("buffer radius must be within 0..40 chunks");
        }
        if (!shield.healthy()) {
            return GenerationBufferStatus.FAIL_CLOSED;
        }

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
                    return GenerationBufferStatus.FAIL_CLOSED;
                }
            }
        }
        return pending.missing().isEmpty()
                ? GenerationBufferStatus.READY
                : GenerationBufferStatus.PENDING;
    }

    /** Refreshes pending readiness away from movement events with a bounded check budget. */
    public synchronized void refresh(int maxChecks) {
        if (maxChecks < 1) {
            throw new IllegalArgumentException("maxChecks must be >= 1");
        }
        int checked = 0;
        for (Map.Entry<String, PendingBuffer> entry : byRequester.entrySet()) {
            if (checked >= maxChecks || !shield.healthy()) {
                return;
            }
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
                } catch (Exception exception) {
                    return;
                }
            }

            if (!pending.unsubmitted().isEmpty() && shield.healthy()) {
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
        }
    }

    public synchronized void removeRequester(String requesterId) {
        byRequester.remove(Objects.requireNonNull(requesterId, "requesterId"));
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

    private record BufferCenter(String worldUuid, int x, int z, int radius) {
    }

    private record PendingBuffer(
            BufferCenter center,
            Set<ChunkKey> missing,
            Set<ChunkKey> unsubmitted) {
    }
}
