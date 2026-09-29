package net.enthusia.frontier.application;

/** Applies and restores per-player view/send distance overrides. */
public interface ViewDistancePort {
    void apply(int viewDistance);

    void restore();
}
