package net.enthusia.frontier.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveThrottlePolicyTest {
    private final List<ThrottleLevel> levels = List.of(
            new ThrottleLevel("healthy", 0.0, new GenerationLimits(32.0, 4)),
            new ThrottleLevel("elevated", 25.0, new GenerationLimits(16.0, 3)),
            new ThrottleLevel("pressured", 35.0, new GenerationLimits(8.0, 2)),
            new ThrottleLevel("critical", 45.0, new GenerationLimits(2.0, 1)));
    private final AdaptiveThrottlePolicy policy = new AdaptiveThrottlePolicy(levels, 2.0);

    @Test
    void escalatesImmediatelyAcrossMultipleBands() {
        assertEquals("healthy", policy.select(20.0, null).name());
        assertEquals("pressured", policy.select(40.0, levels.get(0)).name());
        assertEquals("critical", policy.select(50.0, levels.get(0)).name());
    }

    @Test
    void recoveryUsesHysteresis() {
        ThrottleLevel critical = levels.get(3);
        assertEquals("critical", policy.select(44.0, critical).name());
        assertEquals("pressured", policy.select(42.9, critical).name());
    }

    @Test
    void largeRecoveryCanReturnDirectlyToHealthy() {
        assertEquals("healthy", policy.select(20.0, levels.get(3)).name());
    }
}
