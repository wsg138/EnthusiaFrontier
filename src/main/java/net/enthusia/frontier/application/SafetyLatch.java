package net.enthusia.frontier.application;

/** Persisted fail-closed gate for future destructive cleanup. */
public interface SafetyLatch {
    void trip(String reason);

    boolean isTripped();

    String reason();
}
