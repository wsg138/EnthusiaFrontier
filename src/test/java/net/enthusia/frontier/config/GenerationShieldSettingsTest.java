package net.enthusia.frontier.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.enthusia.frontier.domain.GenerationShieldLevel;
import net.enthusia.frontier.domain.GlobalGenerationLimits;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GenerationShieldSettingsTest {
    @Test
    void loadsValidatedWholeServerBudget() {
        GenerationShieldSettings settings = GenerationShieldSettings.load(validConfig());

        assertTrue(settings.enabled());
        assertEquals(256, settings.queueCapacity());
        assertEquals(2, settings.extraGuardRadiusChunks());
        assertEquals(12, settings.maxGuardRadiusChunks());
        assertEquals(128, settings.refreshChecksPerTick());
        assertEquals(20L, settings.samplePeriodTicks());
        assertEquals(2.0, settings.recoveryHysteresisMspt());
        assertEquals(2, settings.levels().size());
        assertEquals(new GlobalGenerationLimits(0.0, 0), settings.levels().get(1).limits());
    }

    @Test
    void constructorRejectsUnsafeBounds() {
        List<GenerationShieldLevel> levels = List.of(
                new GenerationShieldLevel("healthy", 0.0, new GlobalGenerationLimits(4.0, 2)));

        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 127, 2, 12, 128, 20, 2.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 0, 12, 128, 20, 2.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 3, 2, 128, 20, 2.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 2, 41, 128, 20, 2.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 2, 12, 63, 20, 2.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 2, 12, 128, 0, 2.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 2, 12, 128, 20, -1.0, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationShieldSettings(true, 256, 2, 12, 128, 20, 2.0, List.of()));
    }

    @Test
    void loadRejectsMalformedLevels() {
        YamlConfiguration empty = validConfig();
        empty.set("generation-shield.levels", List.of());
        assertThrows(IllegalArgumentException.class, () -> GenerationShieldSettings.load(empty));

        YamlConfiguration blankName = validConfig();
        blankName.set("generation-shield.levels", List.of(Map.of(
                "name", " ",
                "enter-mspt", 0.0,
                "chunks-per-second", 4.0,
                "max-concurrent", 2)));
        assertThrows(IllegalArgumentException.class, () -> GenerationShieldSettings.load(blankName));

        YamlConfiguration nonNumeric = validConfig();
        nonNumeric.set("generation-shield.levels", List.of(Map.of(
                "name", "healthy",
                "enter-mspt", "fast",
                "chunks-per-second", 4.0,
                "max-concurrent", 2)));
        assertThrows(IllegalArgumentException.class, () -> GenerationShieldSettings.load(nonNumeric));
    }

    private static YamlConfiguration validConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("generation-shield.enabled", true);
        config.set("generation-shield.queue-capacity", 256);
        config.set("generation-shield.extra-guard-radius-chunks", 2);
        config.set("generation-shield.max-guard-radius-chunks", 12);
        config.set("generation-shield.refresh-checks-per-tick", 128);
        config.set("generation-shield.sample-period-ticks", 20L);
        config.set("generation-shield.recovery-hysteresis-mspt", 2.0);
        config.set("generation-shield.levels", List.of(
                Map.of(
                        "name", "healthy",
                        "enter-mspt", 0.0,
                        "chunks-per-second", 4.0,
                        "max-concurrent", 2),
                Map.of(
                        "name", "critical",
                        "enter-mspt", 45.0,
                        "chunks-per-second", 0.0,
                        "max-concurrent", 0)));
        return config;
    }
}
