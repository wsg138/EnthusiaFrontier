package net.enthusia.frontier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.enthusia.frontier.domain.ViewDistanceLevel;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated adaptive player view-distance configuration. */
public record AdaptiveViewDistanceSettings(
        boolean enabled,
        long samplePeriodTicks,
        double recoveryHysteresisMspt,
        int recoveryStableSamples,
        List<ViewDistanceLevel> levels) {

    public AdaptiveViewDistanceSettings {
        levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
        if (samplePeriodTicks < 1) {
            throw new IllegalArgumentException("adaptive-view-distance.sample-period-ticks must be >= 1");
        }
        if (!Double.isFinite(recoveryHysteresisMspt) || recoveryHysteresisMspt < 0.0) {
            throw new IllegalArgumentException("adaptive-view-distance.recovery-hysteresis-mspt must be non-negative and finite");
        }
        if (recoveryStableSamples < 1) {
            throw new IllegalArgumentException("adaptive-view-distance.recovery-stable-samples must be >= 1");
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("adaptive-view-distance.levels must contain at least one level");
        }
    }

    public static AdaptiveViewDistanceSettings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        boolean enabled = config.getBoolean("adaptive-view-distance.enabled", true);
        long samplePeriod = config.getLong("adaptive-view-distance.sample-period-ticks", 5L);
        double hysteresis = config.getDouble("adaptive-view-distance.recovery-hysteresis-mspt", 3.0);
        int stableSamples = config.getInt("adaptive-view-distance.recovery-stable-samples", 60);

        List<ViewDistanceLevel> levels = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("adaptive-view-distance.levels")) {
            String name = requiredString(entry, "name");
            double enterMspt = requiredNumber(entry, "enter-mspt").doubleValue();
            int viewDistance = requiredNumber(entry, "view-distance").intValue();
            levels.add(new ViewDistanceLevel(name, enterMspt, viewDistance));
        }
        if (levels.isEmpty()) {
            levels = defaultLevels();
        }
        return new AdaptiveViewDistanceSettings(enabled, samplePeriod, hysteresis, stableSamples, levels);
    }

    private static List<ViewDistanceLevel> defaultLevels() {
        return List.of(
                new ViewDistanceLevel("healthy", 0.0, 15),
                new ViewDistanceLevel("elevated", 25.0, 12),
                new ViewDistanceLevel("pressured", 35.0, 9),
                new ViewDistanceLevel("critical", 45.0, 7),
                new ViewDistanceLevel("emergency", 49.5, 5));
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing adaptive view-distance level field: " + key);
        }
        return value.toString();
    }

    private static Number requiredNumber(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Adaptive view-distance level field must be numeric: " + key);
        }
        return number;
    }
}
