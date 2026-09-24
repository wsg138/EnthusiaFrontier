package net.enthusia.frontier.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FrontierSettingsTest {
    @Test
    void loadsValidatedSnapshotAndSkipsDisabledWorlds() {
        YamlConfiguration config = validConfig();
        config.set("worlds.disabled.enabled", false);
        config.set("worlds.disabled.core-radius-blocks", 1);

        FrontierSettings settings = FrontierSettings.load(config);

        assertEquals(1, settings.worldPolicies().size());
        assertEquals(new CoreBoundaryPolicy(100_000), settings.worldPolicies().get("world"));
        assertFalse(settings.worldPolicies().containsKey("disabled"));
        assertEquals(2, settings.protectionRadiusChunks());
        assertEquals(256, settings.mutationQueueCapacity());
        assertEquals(32, settings.mutationBatchSize());
        assertTrue(settings.throttleRequired());
        assertEquals(20L, settings.throttleSamplePeriodTicks());
        assertEquals(2.0, settings.recoveryHysteresisMspt());
        assertEquals(2, settings.throttleLevels().size());
        assertFalse(settings.cleanup().enabled());
        assertTrue(settings.cleanup().dryRun());
        assertEquals(30L, settings.cleanup().untouchedRetentionDays());
    }

    @Test
    void constructorRejectsUnsafeBounds() {
        Map<String, CoreBoundaryPolicy> worlds = Map.of("world", new CoreBoundaryPolicy(0));
        FrontierSettings loaded = FrontierSettings.load(validConfig());
        var levels = loaded.throttleLevels();
        CleanupSettings cleanup = loaded.cleanup();

        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(Map.of(), 0, 128, 1, true, 1, 1.0, levels, cleanup));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(worlds, -1, 128, 1, true, 1, 1.0, levels, cleanup));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(worlds, 33, 128, 1, true, 1, 1.0, levels, cleanup));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(worlds, 0, 127, 1, true, 1, 1.0, levels, cleanup));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(worlds, 0, 128, 0, true, 1, 1.0, levels, cleanup));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(worlds, 0, 128, 129, true, 1, 1.0, levels, cleanup));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontierSettings(worlds, 0, 128, 1, true, 0, 1.0, levels, cleanup));
        assertThrows(NullPointerException.class,
                () -> new FrontierSettings(worlds, 0, 128, 1, true, 1, 1.0, levels, null));
    }

    @Test
    void loadRejectsMalformedRequiredConfiguration() {
        YamlConfiguration missingWorlds = new YamlConfiguration();
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(missingWorlds));

        YamlConfiguration negativeCore = validConfig();
        negativeCore.set("worlds.world.core-radius-blocks", -1);
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(negativeCore));

        YamlConfiguration noEnabledWorlds = validConfig();
        noEnabledWorlds.set("worlds.world.enabled", false);
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(noEnabledWorlds));

        YamlConfiguration noLevels = validConfig();
        noLevels.set("throttle.levels", List.of());
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(noLevels));

        YamlConfiguration missingName = validConfig();
        missingName.set("throttle.levels", List.of(Map.of(
                "enter-mspt", 0.0,
                "max-generation-rate", 32.0,
                "max-concurrent-generations", 4)));
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(missingName));

        YamlConfiguration blankName = validConfig();
        blankName.set("throttle.levels", List.of(Map.of(
                "name", " ",
                "enter-mspt", 0.0,
                "max-generation-rate", 32.0,
                "max-concurrent-generations", 4)));
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(blankName));

        YamlConfiguration nonNumeric = validConfig();
        nonNumeric.set("throttle.levels", List.of(Map.of(
                "name", "healthy",
                "enter-mspt", "fast",
                "max-generation-rate", 32.0,
                "max-concurrent-generations", 4)));
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(nonNumeric));

        YamlConfiguration unsafeCleanup = validConfig();
        unsafeCleanup.set("cleanup.enabled", false);
        unsafeCleanup.set("cleanup.dry-run", false);
        assertThrows(IllegalArgumentException.class, () -> FrontierSettings.load(unsafeCleanup));
    }

    private static YamlConfiguration validConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("worlds.world.enabled", true);
        config.set("worlds.world.core-radius-blocks", 100_000);
        config.set("tracking.protection-radius-chunks", 2);
        config.set("tracking.mutation-queue-capacity", 256);
        config.set("tracking.mutation-batch-size", 32);
        config.set("throttle.require-supported-adapter", true);
        config.set("throttle.sample-period-ticks", 20L);
        config.set("throttle.recovery-hysteresis-mspt", 2.0);
        config.set("throttle.levels", List.of(
                Map.of(
                        "name", "healthy",
                        "enter-mspt", 0.0,
                        "max-generation-rate", 32.0,
                        "max-concurrent-generations", 4),
                Map.of(
                        "name", "critical",
                        "enter-mspt", 45.0,
                        "max-generation-rate", 2.0,
                        "max-concurrent-generations", 1)));
        config.set("cleanup.enabled", false);
        config.set("cleanup.dry-run", true);
        config.set("cleanup.untouched-retention-days", 30L);
        return config;
    }
}
