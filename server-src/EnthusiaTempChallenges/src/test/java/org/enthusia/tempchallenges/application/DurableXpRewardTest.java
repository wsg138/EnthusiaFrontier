package org.enthusia.tempchallenges.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableXpRewardTest {
    private final DurableXpReward reward = new DurableXpReward();

    @Test
    void repeatedProjectionNeverDoublePays() throws Exception {
        FakeStore store = new FakeStore();
        assertEquals(DurableXpReward.Result.APPLIED, reward.ensure(250, store));
        assertEquals(DurableXpReward.Result.ALREADY_APPLIED, reward.ensure(250, store));
        assertEquals(250, store.xp);
        assertEquals(250, store.diskXp);
        assertTrue(store.diskMarker);
    }

    @Test
    void crashAfterPlayerSaveBeforeSqliteDeliveryMarkDoesNotDoublePay() throws Exception {
        FakeStore firstProcess = new FakeStore();
        reward.ensure(400, firstProcess);
        FakeStore restarted = FakeStore.fromDisk(firstProcess);
        assertEquals(DurableXpReward.Result.ALREADY_APPLIED, reward.ensure(400, restarted));
        assertEquals(400, restarted.xp);
    }

    @Test
    void failedPlayerSaveCanRetryOnceFromOldDiskState() throws Exception {
        FakeStore firstProcess = new FakeStore();
        firstProcess.failSave = true;
        assertThrows(IllegalStateException.class, () -> reward.ensure(100, firstProcess));
        FakeStore restarted = FakeStore.fromDisk(firstProcess);
        restarted.failSave = false;
        assertEquals(DurableXpReward.Result.APPLIED, reward.ensure(100, restarted));
        assertEquals(100, restarted.diskXp);
        assertTrue(restarted.diskMarker);
    }

    private static final class FakeStore implements DurableXpReward.PlayerStore {
        boolean marker;
        int xp;
        boolean diskMarker;
        int diskXp;
        boolean failSave;

        static FakeStore fromDisk(FakeStore prior) {
            FakeStore store = new FakeStore();
            store.marker = prior.diskMarker;
            store.xp = prior.diskXp;
            store.diskMarker = prior.diskMarker;
            store.diskXp = prior.diskXp;
            return store;
        }

        @Override public boolean hasMarker() { return marker; }

        @Override
        public void applyAndPersist(int amount) {
            marker = true;
            xp += amount;
            persistCurrentState();
        }

        @Override
        public void persistCurrentState() {
            if (failSave) throw new IllegalStateException("simulated disk write failure");
            diskMarker = marker;
            diskXp = xp;
        }
    }
}
