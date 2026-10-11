package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.enthusia.frontier.domain.AdaptiveViewDistancePolicy;
import net.enthusia.frontier.domain.ViewDistanceLevel;
import org.junit.jupiter.api.Test;

class AdaptiveViewDistanceServiceTest {
    @Test
    void pressureDropsImmediatelyAndRecoveryClimbsOneBandAtATime() throws Exception {
        List<ViewDistanceLevel> levels = levels();
        double[] mspt = {20.0};
        RecordingViewDistance port = new RecordingViewDistance();
        AdaptiveViewDistanceService service = new AdaptiveViewDistanceService(
                new AdaptiveViewDistancePolicy(levels, 3.0),
                () -> mspt[0],
                port,
                2);

        assertEquals("healthy", service.sample().name());
        assertEquals(List.of(15), port.applied);

        mspt[0] = 46.0;
        assertEquals("critical", service.sample().name());
        assertEquals(List.of(15, 7), port.applied);

        mspt[0] = 20.0;
        assertEquals("critical", service.sample().name());
        assertEquals(1, service.recoverySamples());
        assertEquals("pressured", service.sample().name());
        assertEquals(List.of(15, 7, 9), port.applied);

        service.sample();
        assertEquals("elevated", service.sample().name());
        service.sample();
        assertEquals("healthy", service.sample().name());
        assertEquals(List.of(15, 7, 9, 12, 15), port.applied);

        mspt[0] = 50.0;
        assertEquals("emergency", service.sample().name());
        assertEquals(5, port.applied.getLast());

        service.restore();
        assertEquals(1, port.restoreCount);
    }

    @Test
    void strongHeadroomRecoversQuicklyButOnlyOneBandPerStep() throws Exception {
        double[] mspt = {46.0};
        RecordingViewDistance port = new RecordingViewDistance();
        AdaptiveViewDistanceService service = new AdaptiveViewDistanceService(
                new AdaptiveViewDistancePolicy(levels(), 3.0),
                () -> mspt[0], port, 4, 2, 8.0);

        assertEquals("critical", service.sample().name());
        mspt[0] = 20.0;
        assertEquals("critical", service.sample().name());
        AdaptiveViewDistanceService.RecoveryStatus status = service.recoveryStatus();
        assertEquals(9, status.nextLevel().viewDistance());
        assertEquals(42.0, status.belowMspt());
        assertEquals(true, status.eligible());
        assertEquals(true, status.fast());
        assertEquals(1, status.stableSamples());
        assertEquals(2, status.requiredSamples());

        assertEquals("pressured", service.sample().name());
        assertEquals("pressured", service.sample().name());
        assertEquals("elevated", service.sample().name());
        // At 20 MSPT, 12->15 is near the 22 MSPT recovery boundary: cautious.
        assertEquals("elevated", service.sample().name());
        assertEquals(false, service.recoveryStatus().fast());
        assertEquals(4, service.recoveryStatus().requiredSamples());
        service.sample();
        service.sample();
        assertEquals("healthy", service.sample().name());
        assertEquals(List.of(7, 9, 12, 15), port.applied);
    }

    @Test
    void cautiousRecoveryWaitsAndPressureResetsTheCounter() throws Exception {
        double[] mspt = {46.0};
        RecordingViewDistance port = new RecordingViewDistance();
        AdaptiveViewDistanceService service = new AdaptiveViewDistanceService(
                new AdaptiveViewDistancePolicy(levels(), 3.0),
                () -> mspt[0], port, 4, 2, 8.0);
        service.sample();
        mspt[0] = 41.0; // Under the 42 MSPT recovery boundary, but only 1 MSPT of headroom.
        service.sample();
        assertEquals(false, service.recoveryStatus().fast());
        assertEquals(4, service.recoveryStatus().requiredSamples());
        assertEquals(1, service.recoverySamples());

        mspt[0] = 43.0;
        service.sample();
        assertEquals(0, service.recoverySamples());
        assertEquals(false, service.recoveryStatus().eligible());

        mspt[0] = 41.0;
        service.sample();
        service.sample();
        service.sample();
        assertEquals("critical", service.currentLevel().name());
        assertEquals("pressured", service.sample().name());
    }

    private static List<ViewDistanceLevel> levels() {
        return List.of(
                new ViewDistanceLevel("healthy", 0.0, 15),
                new ViewDistanceLevel("elevated", 25.0, 12),
                new ViewDistanceLevel("pressured", 35.0, 9),
                new ViewDistanceLevel("critical", 45.0, 7),
                new ViewDistanceLevel("emergency", 49.5, 5));
    }

    private static final class RecordingViewDistance implements ViewDistancePort {
        private final List<Integer> applied = new ArrayList<>();
        private int restoreCount;

        @Override
        public void apply(int viewDistance) {
            applied.add(viewDistance);
        }

        @Override
        public void restore() {
            restoreCount++;
        }
    }
}
