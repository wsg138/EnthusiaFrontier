package net.enthusia.frontier.application;

import net.enthusia.frontier.domain.GenerationLimits;

/** Outbound port for the runtime server generation limiter. */
public interface GenerationThrottlePort {
    void apply(GenerationLimits limits) throws Exception;

    void restore() throws Exception;
}
