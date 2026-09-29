package net.enthusia.frontier.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveViewDistancePolicyTest {
    @Test
    void appliesPressureImmediatelyAndUsesRecoveryHysteresis() {
        AdaptiveViewDistancePolicy policy = new AdaptiveViewDistancePolicy(levels(), 3.0);
        ViewDistanceLevel healthy = policy.select(20.0, null);
        assertEquals("healthy", healthy.name());

        ViewDistanceLevel critical = policy.select(46.0, healthy);
        assertEquals("critical", critical.name());

        assertEquals("critical", policy.select(43.0, critical).name());
        assertEquals("pressured", policy.select(41.9, critical).name());
        assertEquals("healthy", policy.select(20.0, critical).name());
    }

    @Test
    void rejectsUnsafeOrderingAndDistances() {
        assertThrows(IllegalArgumentException.class,
                () -> new ViewDistanceLevel("bad", 0.0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveViewDistancePolicy(List.of(
                        new ViewDistanceLevel("first", 5.0, 15)), 3.0));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveViewDistancePolicy(List.of(
                        new ViewDistanceLevel("healthy", 0.0, 7),
                        new ViewDistanceLevel("busy", 30.0, 12)), 3.0));
    }

    private static List<ViewDistanceLevel> levels() {
        return List.of(
                new ViewDistanceLevel("healthy", 0.0, 15),
                new ViewDistanceLevel("elevated", 25.0, 12),
                new ViewDistanceLevel("pressured", 35.0, 9),
                new ViewDistanceLevel("critical", 45.0, 7),
                new ViewDistanceLevel("emergency", 49.5, 5));
    }
}
