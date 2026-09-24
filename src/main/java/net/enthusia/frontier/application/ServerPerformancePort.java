package net.enthusia.frontier.application;

/** Outbound port for recent server tick performance. */
@FunctionalInterface
public interface ServerPerformancePort {
    double currentAverageMspt();
}
