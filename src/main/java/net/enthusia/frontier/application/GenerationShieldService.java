package net.enthusia.frontier.application;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.GlobalGenerationLimits;

/**
 * Whole-server generation admission controller.
 *
 * <p>The queue is requester-fair, chunk requests are globally deduplicated, and
 * rate/concurrency limits apply once to the entire server rather than once per player.
 * The service performs no sleeps; callers drive {@link #pump()} from a scheduler and
 * tests inject a deterministic monotonic clock.</p>
 */
public final class GenerationShieldService implements AutoCloseable {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int queueCapacity;
    private final Predicate<ChunkKey> managedChunk;
    private final GenerationReadinessPort readiness;
    private final ChunkGenerationPort generation;
    private final BooleanSupplier externalHealthy;
    private final LongSupplier nanoTime;
    private final Consumer<String> failureSink;
    private final Map<String, ArrayDeque<ChunkKey>> queuedByRequester = new HashMap<>();
    private final ArrayDeque<String> requesterOrder = new ArrayDeque<>();
    private final Set<ChunkKey> outstanding = new HashSet<>();

    private GlobalGenerationLimits limits = new GlobalGenerationLimits(0.0, 0);
    private int queued;
    private int inFlight;
    private long started;
    private long completed;
    private long rejected;
    private long deduplicated;
    private long nextAdmissionNanos;
    private boolean scheduleInitialized;
    private boolean failed;
    private boolean stopped;

    public GenerationShieldService(
            int queueCapacity,
            Predicate<ChunkKey> managedChunk,
            GenerationReadinessPort readiness,
            ChunkGenerationPort generation,
            BooleanSupplier externalHealthy,
            LongSupplier nanoTime,
            Consumer<String> failureSink) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1");
        }
        this.queueCapacity = queueCapacity;
        this.managedChunk = Objects.requireNonNull(managedChunk, "managedChunk");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.externalHealthy = Objects.requireNonNull(externalHealthy, "externalHealthy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    public synchronized void setLimits(GlobalGenerationLimits newLimits) {
        Objects.requireNonNull(newLimits, "newLimits");
        boolean wasPaused = limits.paused();
        limits = newLimits;
        if (wasPaused || newLimits.paused()) {
            scheduleInitialized = false;
        }
    }

    public synchronized GenerationAdmission request(String requesterId, ChunkKey key) {
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(key, "key");
        if (requesterId.isBlank()) {
            throw new IllegalArgumentException("requesterId must not be blank");
        }
        if (stopped) {
            rejected++;
            return GenerationAdmission.REJECTED_STOPPED;
        }
        if (!healthy()) {
            rejected++;
            return GenerationAdmission.REJECTED_UNHEALTHY;
        }
        if (!managedChunk.test(key)) {
            return GenerationAdmission.CORE_BYPASS;
        }
        try {
            if (readiness.isReady(key)) {
                return GenerationAdmission.READY;
            }
        } catch (Exception exception) {
            fail("generation readiness lookup failed for " + key, exception);
            rejected++;
            return GenerationAdmission.REJECTED_UNHEALTHY;
        }
        if (!outstanding.add(key)) {
            deduplicated++;
            return GenerationAdmission.DEDUPLICATED;
        }
        if (queued >= queueCapacity) {
            outstanding.remove(key);
            rejected++;
            return GenerationAdmission.REJECTED_CAPACITY;
        }

        ArrayDeque<ChunkKey> requesterQueue = queuedByRequester.computeIfAbsent(requesterId, ignored -> {
            requesterOrder.addLast(requesterId);
            return new ArrayDeque<>();
        });
        requesterQueue.addLast(key);
        queued++;
        if (queued == 1 && inFlight == 0) {
            scheduleInitialized = false;
        }
        return GenerationAdmission.QUEUED;
    }

    /** Starts every request currently allowed by the aggregate rate and concurrency budgets. */
    public synchronized int pump() {
        if (stopped || !healthy() || limits.paused() || queued == 0 || inFlight >= limits.maxConcurrent()) {
            return 0;
        }
        long now = nanoTime.getAsLong();
        if (!scheduleInitialized) {
            nextAdmissionNanos = now;
            scheduleInitialized = true;
        }
        long interval = admissionIntervalNanos(limits.chunksPerSecond());
        int admitted = 0;
        while (queued > 0
                && inFlight < limits.maxConcurrent()
                && now >= nextAdmissionNanos
                && healthy()) {
            ChunkKey key = pollFair();
            if (key == null) {
                break;
            }
            try {
                if (readiness.isReady(key)) {
                    outstanding.remove(key);
                    continue;
                }
                CompletableFuture<Void> future = Objects.requireNonNull(
                        generation.generate(key), "generation port returned null future");
                inFlight++;
                started++;
                admitted++;
                future.whenComplete((ignored, error) -> complete(key, error));
            } catch (Exception exception) {
                outstanding.remove(key);
                fail("could not start generation for " + key, exception);
                break;
            }
            nextAdmissionNanos = saturatingAdd(nextAdmissionNanos, interval);
        }
        if (queued == 0) {
            scheduleInitialized = false;
        }
        return admitted;
    }

    public synchronized GenerationShieldMetrics metrics() {
        return new GenerationShieldMetrics(
                queued, inFlight, started, completed, rejected, deduplicated, healthy());
    }

    public synchronized GlobalGenerationLimits limits() {
        return limits;
    }

    public synchronized boolean healthy() {
        return !failed && !stopped && externalHealthy.getAsBoolean();
    }

    @Override
    public synchronized void close() {
        stopped = true;
        for (ArrayDeque<ChunkKey> queue : queuedByRequester.values()) {
            for (ChunkKey key : queue) {
                outstanding.remove(key);
            }
        }
        queuedByRequester.clear();
        requesterOrder.clear();
        queued = 0;
        scheduleInitialized = false;
    }

    private synchronized void complete(ChunkKey key, Throwable error) {
        if (inFlight > 0) {
            inFlight--;
        }
        if (error != null) {
            outstanding.remove(key);
            fail("generation failed for " + key, error);
            return;
        }
        try {
            readiness.markReady(key);
            completed++;
        } catch (Exception exception) {
            fail("generated chunk could not be marked ready: " + key, exception);
        } finally {
            outstanding.remove(key);
        }
    }

    private ChunkKey pollFair() {
        while (!requesterOrder.isEmpty()) {
            String requesterId = requesterOrder.removeFirst();
            ArrayDeque<ChunkKey> requesterQueue = queuedByRequester.get(requesterId);
            if (requesterQueue == null || requesterQueue.isEmpty()) {
                queuedByRequester.remove(requesterId);
                continue;
            }
            ChunkKey key = requesterQueue.removeFirst();
            queued--;
            if (requesterQueue.isEmpty()) {
                queuedByRequester.remove(requesterId);
            } else {
                requesterOrder.addLast(requesterId);
            }
            return key;
        }
        queued = 0;
        return null;
    }

    private void fail(String message, Throwable error) {
        failed = true;
        failureSink.accept(message + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static long admissionIntervalNanos(double chunksPerSecond) {
        double interval = NANOS_PER_SECOND / chunksPerSecond;
        if (interval >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) Math.ceil(interval));
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
