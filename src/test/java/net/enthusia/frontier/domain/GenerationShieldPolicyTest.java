package net.enthusia.frontier.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationShieldPolicyTest {
    private final List<GenerationShieldLevel> levels = List.of(
            new GenerationShieldLevel("healthy", 0.0, new GlobalGenerationLimits(8.0, 4)),
            new GenerationShieldLevel("pressured", 35.0, new GlobalGenerationLimits(2.0, 2)),
            new GenerationShieldLevel("critical", 45.0, new GlobalGenerationLimits(0.0, 0)));
    private final GenerationShieldPolicy policy = new GenerationShieldPolicy(levels, 2.0);

    @Test
    void supportsTrueZeroAdmissionCriticalLevel() {
        assertEquals("critical", policy.select(50.0, levels.get(0)).name());
        assertEquals(new GlobalGenerationLimits(0.0, 0), levels.get(2).limits());
    }

    @Test
    void recoversOnlyAfterHysteresisBoundary() {
        assertEquals("critical", policy.select(44.0, levels.get(2)).name());
        assertEquals("pressured", policy.select(42.9, levels.get(2)).name());
        assertEquals("healthy", policy.select(20.0, levels.get(2)).name());
    }

    @Test
    void validatesGlobalLimitsAndBands() {
        assertThrows(IllegalArgumentException.class, () -> new GlobalGenerationLimits(-1.0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalGenerationLimits(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalGenerationLimits(1.0, -1));
        assertThrows(IllegalArgumentException.class, () -> new GenerationShieldPolicy(List.of(), 1.0));
    }
}
