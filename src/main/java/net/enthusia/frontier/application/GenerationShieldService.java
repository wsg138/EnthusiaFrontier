package net.enthusia.frontier.application;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
 * Actual newly generated managed chunks also feed a global debt budget so dependency
 * generation cannot multiply the effective chunks-per-second ceiling.</p>
 */
public final class GenerationShieldService implements AutoCloseable {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final double DEBT_EPSILON = 1.0e-9;

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
    private final Set<ChunkKey> observationCredits = new HashSet<>();
    private final Set<ChunkKey> pendingObservations = new HashSet<>();

    private GlobalGenerationLimits limits = new GlobalGenerationLimits(0.0, 0);
    private int queued;
    private int inFlight;
    private long started;
    private long completed;
    private long rejected;
    private long deduplicated;
    private long observedGenerated;
    private double observedDebtChunks;
    private long debtUpdatedNanos;
    private boolean debtClockInitialized;
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
        recoverObservedDebt(nanoTime.getAsLong());
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
        if (!healthy()) {
            rejected++;
            return GenerationAdmission.REJECTED_UNHEALTHY;
        }
        if (pendingObservations.contains(key)) {
            deduplicated++;
            return GenerationAdmission.DEDUPLICATED;
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

    /** Removes not-yet-started work and returns how many queued chunks were dropped. */
    public synchronized int cancelQueued(String requesterId) {
        return cancelQueuedKeys(requesterId).size();
    }

    /**
     * Removes not-yet-started generation work owned by one requester.
     *
     * <p>In-flight work is intentionally left alone because Paper generation futures are
     * not safely cancellable. Returned keys have been released from global deduplication;
     * callers that know another pending requester still needs one of those chunks can
     * re-submit it immediately.</p>
     *
     * @return immutable set of queued chunks that were removed
     */
    public synchronized Set<ChunkKey> cancelQueuedKeys(String requesterId) {
        String requester = Objects.requireNonNull(requesterId, "requesterId");
        ArrayDeque<ChunkKey> requesterQueue = queuedByRequester.remove(requester);
        requesterOrder.removeIf(requester::equals);
        if (requesterQueue == null || requesterQueue.isEmpty()) {
            return Set.of();
        }

        Set<ChunkKey> released = Set.copyOf(requesterQueue);
        for (ChunkKey key : released) {
            outstanding.remove(key);
        }
        queued = Math.max(0, queued - released.size());
        if (queued == 0) {
            scheduleInitialized = false;
        }
        return released;
    }

    /**
     * Accounts for an actual newly generated managed chunk observed by the platform event layer.
     * Cost is charged immediately, but hot readiness is withheld until the lifecycle-ledger
     * mutation for this exact observation has durably committed. The requested chunk of each
     * started admission consumes its one matching credit; every additional generated chunk
     * creates global debt that future admissions must repay.
     *
     * @return true only for the first pending observation of a not-yet-ready managed chunk
     */
    public synchronized boolean observeGenerated(ChunkKey key, CompletionStage<Void> durableCommit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(durableCommit, "durableCommit");
        if (stopped || !managedChunk.test(key)) {
            return false;
        }
        try {
            if (readiness.isReady(key)) {
                return false;
            }
        } catch (Exception exception) {
            fail("generation readiness lookup failed for observed " + key, exception);
            return false;
        }
        if (!pendingObservations.add(key)) {
            return false;
        }

        recoverObservedDebt(nanoTime.getAsLong());
        observedGenerated++;
        if (!observationCredits.remove(key)) {
            observedDebtChunks += 1.0;
        }
        durableCommit.whenComplete((ignored, error) -> observedGenerationCommitted(key, error));
        return true;
    }

    /** Starts every request currently allowed by aggregate rate, observed-cost and concurrency budgets. */
    public synchronized int pump() {
        if (stopped || !healthy() || limits.paused() || queued == 0 || inFlight >= limits.maxConcurrent()) {
            return 0;
        }
        long now = nanoTime.getAsLong();
        recoverObservedDebt(now);
        if (observedDebtChunks > DEBT_EPSILON) {
            return 0;
        }
        if (!scheduleInitialized) {
            nextAdmissionNanos = now;
            scheduleInitialized = true;
        }
        long interval = admissionIntervalNanos(limits.chunksPerSecond());
        int admitted = 0;
        while (queued > 0
                && inFlight < limits.maxConcurrent()
                && now >= nextAdmissionNanos
                && observedDebtChunks <= DEBT_EPSILON
                && healthy()) {
            ChunkKey key = pollFair();
            if (key == null) {
                break;
            }
            if (pendingObservations.contains(key)) {
                outstanding.remove(key);
                continue;
            }
            try {
                if (readiness.isReady(key)) {
                    outstanding.remove(key);
                    continue;
                }
                observationCredits.add(key);
                CompletableFuture<Void> future = Objects.requireNonNull(
                        generation.generate(key), "generation port returned null future");
                inFlight++;
                started++;
                admitted++;
                future.whenComplete((ignored, error) -> generationCompleted(key, error));
            } catch (Exception exception) {
                observationCredits.remove(key);
                outstanding.remove(key);
                fail("could not start generation for " + key, exception);
                break;
            }
            nextAdmissionNanos = saturatingAdd(Math.max(nextAdmissionNanos, now), interval);
        }
        if (queued == 0) {
            scheduleInitialized = false;
        }
        return admitted;
    }

    public synchronized GenerationShieldMetrics metrics() {
        recoverObservedDebt(nanoTime.getAsLong());
        return new GenerationShieldMetrics(
                queued,
                inFlight,
                started,
                completed,
                rejected,
                deduplicated,
                observedGenerated,
                observedDebtChunks,
                healthy());
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
        observationCredits.clear();
        pendingObservations.clear();
        queued = 0;
        scheduleInitialized = false;
    }

    private synchronized void observedGenerationCommitted(ChunkKey key, Throwable durabilityError) {
        pendingObservations.remove(key);
        if (durabilityError != null) {
            fail("generated chunk lifecycle durability failed for " + key, durabilityError);
            return;
        }
        if (stopped) {
            return;
        }
        try {
            readiness.observeReady(key);
        } catch (RuntimeException exception) {
            fail("generation readiness observation failed for " + key, exception);
        }
    }

    private void generationCompleted(ChunkKey key, Throwable generationError) {
        if (generationError != null) {
            finishFailure(key, "generation failed for " + key, generationError);
            return;
        }

        CompletableFuture<Void> durableReady;
        try {
            durableReady = Objects.requireNonNull(
                    readiness.markReady(key), "readiness port returned null future");
        } catch (RuntimeException exception) {
            finishFailure(key, "generated chunk could not begin durable readiness commit: " + key, exception);
            return;
        }
        durableReady.whenComplete((ignored, readinessError) -> {
            if (readinessError != null) {
                finishFailure(key, "generated chunk could not be marked durably ready: " + key, readinessError);
            } else {
                finishSuccess(key);
            }
        });
    }

    private synchronized void finishSuccess(ChunkKey key) {
        observationCredits.remove(key);
        if (inFlight > 0) {
            inFlight--;
        }
        completed++;
        outstanding.remove(key);
    }

    private synchronized void finishFailure(ChunkKey key, String message, Throwable error) {
        observationCredits.remove(key);
        if (inFlight > 0) {
            inFlight--;
        }
        outstanding.remove(key);
        fail(message, error);
    }

    private void recoverObservedDebt(long now) {
        if (!debtClockInitialized) {
            debtUpdatedNanos = now;
            debtClockInitialized = true;
            return;
        }
        if (now <= debtUpdatedNanos) {
            return;
        }
        if (observedDebtChunks > 0.0 && limits.chunksPerSecond() > 0.0) {
            double elapsedSeconds = (now - debtUpdatedNanos) / (double) NANOS_PER_SECOND;
            observedDebtChunks = Math.max(
                    0.0,
                    observedDebtChunks - elapsedSeconds * limits.chunksPerSecond());
            if (observedDebtChunks < DEBT_EPSILON) {
                observedDebtChunks = 0.0;
            }
        }
        debtUpdatedNanos = now;
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
