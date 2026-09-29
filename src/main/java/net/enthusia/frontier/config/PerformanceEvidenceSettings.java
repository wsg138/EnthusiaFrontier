package net.enthusia.frontier.config;

import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;

/** Configuration for low-frequency live performance evidence sampling. */
public record PerformanceEvidenceSettings(
        boolean enabled,
        long samplePeriodTicks,
        int retentionSamples,
        boolean csvEnabled) {

    public PerformanceEvidenceSettings {
        if (samplePeriodTicks < 20) {
            throw new IllegalArgumentException("performance-evidence.sample-period-ticks must be >= 20");
        }
        if (retentionSamples < 12) {
            throw new IllegalArgumentException("performance-evidence.retention-samples must be >= 12");
        }
    }

    public static PerformanceEvidenceSettings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new PerformanceEvidenceSettings(
                config.getBoolean("performance-evidence.enabled", true),
                config.getLong("performance-evidence.sample-period-ticks", 100L),
                config.getInt("performance-evidence.retention-samples", 720),
                config.getBoolean("performance-evidence.csv-enabled", true));
    }
}
