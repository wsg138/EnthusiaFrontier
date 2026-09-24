package net.enthusia.frontier.application;

/** Outcome of a conservative physical region-container reclaim attempt. */
public enum RegionReclaimResult {
    RECLAIMED,
    ABSENT,
    NOT_EMPTY,
    DEFERRED_OPEN,
    UNSUPPORTED
}
