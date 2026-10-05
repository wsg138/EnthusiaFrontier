package net.enthusia.frontier.application;

/** Applies and restores per-player loading view-distance overrides. */
public interface ViewDistancePort {
    void apply(int viewDistance);

    void restore();
}
