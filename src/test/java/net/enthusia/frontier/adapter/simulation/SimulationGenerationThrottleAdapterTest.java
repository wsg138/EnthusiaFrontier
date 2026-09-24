package net.enthusia.frontier.adapter.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.enthusia.frontier.domain.GenerationLimits;
import org.junit.jupiter.api.Test;

class SimulationGenerationThrottleAdapterTest {
    @Test
    void recordsAppliedLimitsAndRestoresToEmptyState() throws Exception {
        SimulationGenerationThrottleAdapter adapter = new SimulationGenerationThrottleAdapter();
        GenerationLimits limits = new GenerationLimits(12.5, 3);

        assertNull(adapter.currentLimits());
        adapter.apply(limits);
        assertEquals(limits, adapter.currentLimits());
        adapter.restore();
        assertNull(adapter.currentLimits());
    }
}
