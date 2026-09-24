package net.enthusia.frontier.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ArrayBlockingQueue<FrontierMutation> queue;
    private final int batchSize;
    private final Consumer<String> errorSink;
    private final AtomicBoolean accepting = new AtomicBoolean();
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
        Objects.requireNonNull(mutation, "mutation");
        if (!accepting.get()) {
            safetyLatch.trip("mutation submitted while ledger journal was not accepting writes");
            return false;
        }
        if (!queue.offer(mutation)) {
            safetyLatch.trip("frontier mutation queue overflow; activity history may be incomplete");
            return false;
        }
        return true;
    }

    public int queueDepth() {
        return queue.size();
    }

    public boolean isHealthy() {
        Thread currentWorker = worker;
        return accepting.get() && currentWorker != null && currentWorker.isAlive() && !safetyLatch.isTripped();
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
        }
        if (currentWorker.isAlive()) {
            safetyLatch.trip("frontier ledger did not drain before shutdown timeout");
            currentWorker.interrupt();
        }
    }

    private void runWorker() {
        List<FrontierMutation> batch = new ArrayList<>(batchSize);
        while (accepting.get() || !queue.isEmpty() || !batch.isEmpty()) {
            if (batch.isEmpty()) {
                try {
                    FrontierMutation first = queue.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
                    return;
                }
            }

            try {
                repository.applyBatch(batch);
                batch.clear();
            } catch (Exception exception) {
                safetyLatch.trip("frontier ledger durable write failed: " + exception.getClass().getSimpleName());
                errorSink.accept("Frontier ledger write failed; destructive cleanup is latched unsafe: " + exception);
                try {
                    TimeUnit.MILLISECONDS.sleep(FAILURE_BACKOFF.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
