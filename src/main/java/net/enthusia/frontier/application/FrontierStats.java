package net.enthusia.frontier.application;

/** Bounded diagnostic counters from the durable chunk ledger. */
public record FrontierStats(long temporaryChunks, long protectedChunks, long deletedChunks) {
    public FrontierStats {
        if (temporaryChunks < 0 || protectedChunks < 0 || deletedChunks < 0) {
            throw new IllegalArgumentException("Frontier statistics cannot be negative");
        }
    }
}
