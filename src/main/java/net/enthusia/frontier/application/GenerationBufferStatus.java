package net.enthusia.frontier.application;

/** Whether a player/vehicle may advance into a requested generated-buffer center. */
public enum GenerationBufferStatus {
    READY,
    PENDING,
    FAIL_CLOSED
}
