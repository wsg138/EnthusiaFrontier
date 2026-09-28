package net.enthusia.frontier.application;

/** Result of asking Frontier to make one chunk safe for frontier travel. */
public enum GenerationAdmission {
    READY,
    CORE_BYPASS,
    QUEUED,
    DEDUPLICATED,
    REJECTED_CAPACITY,
    REJECTED_UNHEALTHY,
    REJECTED_STOPPED
}
