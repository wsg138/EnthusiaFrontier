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
