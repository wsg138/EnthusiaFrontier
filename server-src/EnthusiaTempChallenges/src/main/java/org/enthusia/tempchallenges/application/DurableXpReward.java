package org.enthusia.tempchallenges.application;

/**
 * Crash-idempotent XP projection. The platform adapter stores an XP marker in
 * the same durable player data that contains the player's experience. A retry
 * after a crash can therefore distinguish "already saved" from "not saved".
 */
public final class DurableXpReward {
    public enum Result { APPLIED, ALREADY_APPLIED, NOT_REQUIRED }

    public interface PlayerStore {
        boolean hasMarker();
        void applyAndPersist(int amount) throws Exception;
        void persistCurrentState() throws Exception;
    }

    public Result ensure(int amount, PlayerStore store) throws Exception {
        if (amount <= 0) return Result.NOT_REQUIRED;
        if (store.hasMarker()) {
            // A previous process may have saved player data and crashed before
            // marking SQLite delivery complete. Re-save and converge without XP.
            store.persistCurrentState();
            return Result.ALREADY_APPLIED;
        }
        store.applyAndPersist(amount);
        return Result.APPLIED;
    }
}
