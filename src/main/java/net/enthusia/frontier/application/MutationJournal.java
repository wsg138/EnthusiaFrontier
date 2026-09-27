package net.enthusia.frontier.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bounded single-writer journal that keeps SQLite work off the Minecraft tick thread.
 * Any lost/uncertain mutation permanently trips the cleanup safety latch.
 */
public final class MutationJournal implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final FrontierRepository repository;
    private final SafetyLatch safetyLatch;
    private final ArrayBlockingQueue<JournalEntry> queue;
    private final int batchSize;
    private final Consumer<String> errorSink;
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicInteger pendingMutations = new AtomicInteger();
    private final Set<CompletableFuture<Void>> pendingDurable = ConcurrentHashMap.newKeySet();
    private volatile Thread worker;

    public MutationJournal(
            FrontierRepository repository,
            SafetyLatch safetyLatch,
            int queueCapacity,
            int batchSize,
            Consumer<String> errorSink) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.safetyLatch = Objects.requireNonNull(safetyLatch, "safetyLatch");
        this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
        if (queueCapacity < 128) {
            throw new IllegalArgumentException("queueCapacity must be >= 128");
        }
        if (batchSize < 1 || batchSize > queueCapacity) {
            throw new IllegalArgumentException("batchSize must be within 1..queueCapacity");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (accepting.get()) {
            throw new IllegalStateException("Mutation journal is already running");
        }
        accepting.set(true);
        worker = Thread.ofPlatform().daemon(true).name("EnthusiaFrontier-Ledger").start(this::runWorker);
    }

    public boolean submit(FrontierMutation mutation) {
        return enqueue(Objects.requireNonNull(mutation, "mutation"), null);
    }

    /**
     * Submits a mutation and returns a future that completes only after the batch containing
     * that exact mutation has durably committed. A failed write completes the future
     * exceptionally immediately even though the journal keeps retrying the batch for data
     * preservation; the safety latch remains tripped so dependent runtime paths fail closed.
     */
    public CompletableFuture<Void> submitDurable(FrontierMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        CompletableFuture<Void> committed = new CompletableFuture<>();
        if (!enqueue(mutation, committed)) {
            committed.completeExceptionally(
                    new IllegalStateException("frontier mutation was not accepted for durable commit"));
        }
        return committed;
    }

    public int queueDepth() {
        return queue.size();
    }

    public int pendingMutations() {
        return pendingMutations.get();
    }

    public boolean isHealthy() {
        Thread currentWorker = worker;
        return accepting.get() && currentWorker != null && currentWorker.isAlive() && !safetyLatch.isTripped();
    }

    /** True only after every accepted mutation has durably committed. */
    public boolean isIdle() {
        return pendingMutations.get() == 0 && queue.isEmpty() && isHealthy();
    }

    @Override
    public synchronized void close() {
        if (!accepting.getAndSet(false)) {
            return;
        }
        Thread currentWorker = worker;
        if (currentWorker == null) {
            return;
        }
        try {
            currentWorker.join(CLOSE_WAIT.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            safetyLatch.trip("interrupted while waiting for frontier ledger shutdown");
            failPendingDurable(new IllegalStateException("frontier ledger shutdown was interrupted", exception));
        }
        if (currentWorker.isAlive()) {
            safetyLatch.trip("frontier ledger did not drain before shutdown timeout");
            failPendingDurable(new IllegalStateException("frontier ledger did not drain before shutdown timeout"));
            currentWorker.interrupt();
        }
    }

    private boolean enqueue(FrontierMutation mutation, CompletableFuture<Void> durableCommit) {
        if (!accepting.get()) {
            safetyLatch.trip("mutation submitted while ledger journal was not accepting writes");
            return false;
        }
        JournalEntry entry = new JournalEntry(mutation, durableCommit);
        if (durableCommit != null) {
            pendingDurable.add(durableCommit);
        }
        pendingMutations.incrementAndGet();
        if (!queue.offer(entry)) {
            pendingMutations.decrementAndGet();
            if (durableCommit != null) {
                pendingDurable.remove(durableCommit);
            }
            safetyLatch.trip("frontier mutation queue overflow; activity history may be incomplete");
            return false;
        }
        return true;
    }

    private void runWorker() {
        List<JournalEntry> batch = new ArrayList<>(batchSize);
        List<FrontierMutation> mutations = new ArrayList<>(batchSize);
        while (accepting.get() || !queue.isEmpty() || !batch.isEmpty()) {
            if (batch.isEmpty()) {
                try {
                    JournalEntry first = queue.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    if (accepting.get() || !queue.isEmpty()) {
                        safetyLatch.trip("frontier ledger worker interrupted with pending mutations");
                    }
                    failPendingDurable(new IllegalStateException("frontier ledger worker interrupted", exception));
                    return;
                }
            }

            mutations.clear();
            for (JournalEntry entry : batch) {
                mutations.add(entry.mutation());
            }
            try {
                repository.applyBatch(mutations);
                pendingMutations.addAndGet(-batch.size());
                for (JournalEntry entry : batch) {
                    completeDurable(entry, null);
                }
                batch.clear();
            } catch (Exception exception) {
                safetyLatch.trip("frontier ledger durable write failed: " + exception.getClass().getSimpleName());
                errorSink.accept("Frontier ledger write failed; destructive cleanup is latched unsafe: " + exception);
                IllegalStateException durabilityFailure = new IllegalStateException(
                        "frontier ledger durable write failed", exception);
                for (JournalEntry entry : batch) {
                    completeDurable(entry, durabilityFailure);
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(FAILURE_BACKOFF.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failPendingDurable(new IllegalStateException(
                            "frontier ledger retry was interrupted", interrupted));
                    return;
                }
            }
        }
    }

    private void completeDurable(JournalEntry entry, Throwable failure) {
        CompletableFuture<Void> durableCommit = entry.durableCommit();
        if (durableCommit == null) {
            return;
        }
        if (failure == null) {
            durableCommit.complete(null);
        } else {
            durableCommit.completeExceptionally(failure);
        }
        pendingDurable.remove(durableCommit);
    }

    private void failPendingDurable(Throwable failure) {
        for (CompletableFuture<Void> durableCommit : pendingDurable) {
            durableCommit.completeExceptionally(failure);
            pendingDurable.remove(durableCommit);
        }
    }

    private record JournalEntry(FrontierMutation mutation, CompletableFuture<Void> durableCommit) {
        private JournalEntry {
            Objects.requireNonNull(mutation, "mutation");
        }
    }
}
