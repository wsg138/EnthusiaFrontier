package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;
import org.junit.jupiter.api.Test;

class MutationJournalTest {
    @Test
    void rejectsUnsafeConstructorBoundsAndSubmissionsBeforeStart() {
        RecordingRepository repository = new RecordingRepository();
        RecordingLatch latch = new RecordingLatch();

        assertThrows(IllegalArgumentException.class,
                () -> new MutationJournal(repository, latch, 127, 1, ignored -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> new MutationJournal(repository, latch, 128, 0, ignored -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> new MutationJournal(repository, latch, 128, 129, ignored -> { }));

        MutationJournal journal = new MutationJournal(repository, latch, 128, 8, ignored -> { });
        assertFalse(journal.submit(generated(0)));
        assertTrue(latch.isTripped());
        assertFalse(journal.isHealthy());
        assertFalse(journal.isIdle());
        journal.close();
    }

    @Test
    void runningJournalBatchesDurablyAndExposesExactPendingState() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        RecordingLatch latch = new RecordingLatch();
        MutationJournal journal = new MutationJournal(repository, latch, 128, 16, ignored -> { });

        journal.start();
        assertThrows(IllegalStateException.class, journal::start);
        assertTrue(journal.isHealthy());
        assertTrue(journal.isIdle());
        for (int index = 0; index < 20; index++) {
            assertTrue(journal.submit(generated(index)));
        }
        assertTrue(repository.firstApply.await(2, TimeUnit.SECONDS));
        assertTrue(await(journal::isIdle, 2_000));
        assertEquals(0, journal.pendingMutations());
        journal.close();

        assertEquals(20, repository.applied.size());
        assertFalse(latch.isTripped());
        assertFalse(journal.isHealthy());
        assertFalse(journal.submit(generated(99)));
    }

    @Test
    void failedWriteTripsLatchReportsErrorAndRetriesSameBatch() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        repository.failuresRemaining.set(1);
        RecordingLatch latch = new RecordingLatch();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        MutationJournal journal = new MutationJournal(repository, latch, 128, 8, errors::add);

        journal.start();
        assertTrue(journal.submit(generated(1)));
        assertTrue(await(() -> latch.isTripped() && repository.applied.size() == 1, 3_000));
        journal.close();

        assertTrue(latch.reason().contains("durable write failed"));
        assertTrue(errors.stream().anyMatch(message -> message.contains("destructive cleanup is latched unsafe")));
        assertTrue(repository.applyCalls.get() >= 2);
    }

    @Test
    void queueOverflowTripsSafetyLatchInsteadOfDroppingSilently() throws Exception {
        BlockingRepository repository = new BlockingRepository();
        RecordingLatch latch = new RecordingLatch();
        MutationJournal journal = new MutationJournal(repository, latch, 128, 1, ignored -> { });

        journal.start();
        assertTrue(journal.submit(generated(-1)));
        assertTrue(repository.entered.await(2, TimeUnit.SECONDS));
        for (int index = 0; index < 128; index++) {
            assertTrue(journal.submit(generated(index)));
        }
        assertFalse(journal.submit(generated(129)));
        assertTrue(latch.reason().contains("queue overflow"));
        assertTrue(journal.pendingMutations() >= 128);

        repository.release.countDown();
        journal.close();
    }

    private static FrontierMutation generated(int x) {
        return new FrontierMutation.Generated(new ChunkKey("world", x, 0), Instant.EPOCH);
    }

    private static boolean await(Check check, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (check.value()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return check.value();
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }

    private static final class RecordingLatch implements SafetyLatch {
        private volatile String reason;

        @Override
        public void trip(String newReason) {
            if (reason == null) {
                reason = newReason;
            }
        }

        @Override
        public boolean isTripped() {
            return reason != null;
        }

        @Override
        public String reason() {
            return reason == null ? "none" : reason;
        }
    }

    private static class RecordingRepository implements FrontierRepository {
        private final List<FrontierMutation> applied = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstApply = new CountDownLatch(1);
        private final AtomicInteger applyCalls = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        @Override
        public void initialize() {
        }

        @Override
        public void applyBatch(List<FrontierMutation> mutations) throws Exception {
            applyCalls.incrementAndGet();
            firstApply.countDown();
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IOException("planned failure");
            }
            applied.addAll(mutations);
        }

        @Override
        public List<CleanupCandidate> findCleanupCandidates(String worldUuid, Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<RegionKey> findDeletedRegions(String worldUuid, int limit) {
            return List.of();
        }

        @Override
        public boolean isProtected(ChunkKey key) {
            return false;
        }

        @Override
        public boolean isDeleted(ChunkKey key) {
            return false;
        }

        @Override
        public FrontierStats stats() {
            return new FrontierStats(0, 0, 0);
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingRepository implements FrontierRepository {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void initialize() {
        }

        @Override
        public void applyBatch(List<FrontierMutation> mutations) throws Exception {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("test release timeout");
                }
            }
        }

        @Override
        public List<CleanupCandidate> findCleanupCandidates(String worldUuid, Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<RegionKey> findDeletedRegions(String worldUuid, int limit) {
            return List.of();
        }

        @Override
        public boolean isProtected(ChunkKey key) {
            return false;
        }

        @Override
        public boolean isDeleted(ChunkKey key) {
            return false;
        }

        @Override
        public FrontierStats stats() {
            return new FrontierStats(0, 0, 0);
        }

        @Override
        public void close() {
        }
    }
}
