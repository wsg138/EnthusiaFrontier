package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.GenerationShieldLevel;
import net.enthusia.frontier.domain.GenerationShieldPolicy;
import net.enthusia.frontier.domain.GlobalGenerationLimits;
import org.junit.jupiter.api.Test;

class GenerationShieldControllerTest {
    @Test
    void samplesMsptAndAppliesAggregateLimitsWithHysteresis() {
        AtomicReference<Double> mspt = new AtomicReference<>(20.0);
        GenerationShieldService shield = new GenerationShieldService(
                16,
                ignored -> true,
                new ReadyPort(),
                ignored -> CompletableFuture.completedFuture(null),
                () -> true,
                () -> 0L,
                ignored -> { });
        List<GenerationShieldLevel> levels = List.of(
                new GenerationShieldLevel("healthy", 0.0, new GlobalGenerationLimits(8.0, 4)),
                new GenerationShieldLevel("pressured", 35.0, new GlobalGenerationLimits(2.0, 2)),
                new GenerationShieldLevel("critical", 45.0, new GlobalGenerationLimits(0.0, 0)));
        GenerationShieldController controller = new GenerationShieldController(
                new GenerationShieldPolicy(levels, 2.0), mspt::get, shield);

        assertEquals("healthy", controller.sample().name());
        assertEquals(new GlobalGenerationLimits(8.0, 4), shield.limits());
        assertEquals(20.0, controller.lastMspt());

        mspt.set(50.0);
        assertEquals("critical", controller.sample().name());
        assertEquals(new GlobalGenerationLimits(0.0, 0), shield.limits());
        assertEquals("critical", controller.currentLevel().name());

        mspt.set(44.0);
        assertEquals("critical", controller.sample().name());
        mspt.set(42.9);
        assertEquals("pressured", controller.sample().name());
        assertEquals(new GlobalGenerationLimits(2.0, 2), shield.limits());
    }

    private static final class ReadyPort implements GenerationReadinessPort {
        @Override
        public boolean isReady(ChunkKey key) {
            return false;
        }

        @Override
        public CompletableFuture<Void> markReady(ChunkKey key) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
