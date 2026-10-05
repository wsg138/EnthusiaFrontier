package net.enthusia.frontier.application;

/** Applies and restores Frontier's per-player adaptive view/send footprint. */
public interface ViewDistancePort {
    void apply(int viewDistance);

    void restore();
}
