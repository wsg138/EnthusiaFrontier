package net.enthusia.frontier.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AdaptivePerformanceSettingsTest {
    @Test
    void missingSectionsUseLiveSafeDefaults() {
        YamlConfiguration config = new YamlConfiguration();

        AdaptiveViewDistanceSettings view = AdaptiveViewDistanceSettings.load(config);
        PerformanceEvidenceSettings evidence = PerformanceEvidenceSettings.load(config);

        assertTrue(view.enabled());
        assertEquals(5L, view.samplePeriodTicks());
        assertEquals(60, view.recoveryStableSamples());
        assertEquals(List.of(15, 12, 9, 7, 5),
                view.levels().stream().map(level -> level.viewDistance()).toList());
        assertTrue(evidence.enabled());
        assertEquals(100L, evidence.samplePeriodTicks());
        assertEquals(720, evidence.retentionSamples());
        assertTrue(evidence.csvEnabled());
    }

    @Test
    void customViewLevelsLoadAndInvalidSettingsFail() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("adaptive-view-distance.enabled", true);
        config.set("adaptive-view-distance.sample-period-ticks", 10L);
        config.set("adaptive-view-distance.recovery-hysteresis-mspt", 4.0);
        config.set("adaptive-view-distance.recovery-stable-samples", 8);
        config.set("adaptive-view-distance.levels", List.of(
                Map.of("name", "healthy", "enter-mspt", 0.0, "view-distance", 14),
                Map.of("name", "busy", "enter-mspt", 40.0, "view-distance", 7)));

        AdaptiveViewDistanceSettings loaded = AdaptiveViewDistanceSettings.load(config);
        assertEquals(2, loaded.levels().size());
        assertEquals(14, loaded.levels().get(0).viewDistance());
        assertEquals(7, loaded.levels().get(1).viewDistance());

        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveViewDistanceSettings(true, 0, 3.0, 1, loaded.levels()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveViewDistanceSettings(true, 5, -1.0, 1, loaded.levels()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveViewDistanceSettings(true, 5, 3.0, 0, loaded.levels()));
        assertThrows(IllegalArgumentException.class,
                () -> new PerformanceEvidenceSettings(true, 19, 12, true));
        assertThrows(IllegalArgumentException.class,
                () -> new PerformanceEvidenceSettings(true, 100, 11, true));
    }
}
