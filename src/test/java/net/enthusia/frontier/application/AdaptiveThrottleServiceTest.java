package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.enthusia.frontier.domain.AdaptiveThrottlePolicy;
import net.enthusia.frontier.domain.GenerationLimits;
import net.enthusia.frontier.domain.ThrottleLevel;
import org.junit.jupiter.api.Test;

class AdaptiveThrottleServiceTest {
    @Test
    void onlyWritesLimitsWhenBandChangesAndRestores() throws Exception {
        List<ThrottleLevel> levels = List.of(
                new ThrottleLevel("healthy", 0.0, new GenerationLimits(32.0, 4)),
                new ThrottleLevel("critical", 45.0, new GenerationLimits(2.0, 1)));
        double[] mspt = {20.0};
        RecordingThrottle port = new RecordingThrottle();
        AdaptiveThrottleService service = new AdaptiveThrottleService(
                new AdaptiveThrottlePolicy(levels, 2.0),
                () -> mspt[0],
                port);

        service.sample();
        service.sample();
        assertEquals(1, port.applied.size());

        mspt[0] = 50.0;
        service.sample();
        assertEquals(2, port.applied.size());
        assertEquals(new GenerationLimits(2.0, 1), port.applied.get(1));

        service.restore();
        assertEquals(1, port.restoreCount);
    }

    private static final class RecordingThrottle implements GenerationThrottlePort {
        private final List<GenerationLimits> applied = new ArrayList<>();
        private int restoreCount;

        @Override
        public void apply(GenerationLimits limits) {
            applied.add(limits);
        }

        @Override
        public void restore() {
            restoreCount++;
        }
    }
}
