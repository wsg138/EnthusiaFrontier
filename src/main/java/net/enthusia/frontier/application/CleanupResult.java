package net.enthusia.frontier.application;

/** Result of one bounded cleanup candidate attempt. */
public enum CleanupResult {
    DRY_RUN,
    CLEARED,
    PROTECTED,
    DEFERRED,
    LATCHED
}
