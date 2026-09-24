package net.enthusia.frontier.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.enthusia.frontier.domain.GenerationLimits;
import net.enthusia.frontier.domain.ThrottleLevel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated immutable configuration snapshot. */
public record FrontierSettings(
        Map<String, CoreBoundaryPolicy> worldPolicies,
        int protectionRadiusChunks,
        int mutationQueueCapacity,
        int mutationBatchSize,
        boolean throttleRequired,
        long throttleSamplePeriodTicks,
        double recoveryHysteresisMspt,
        List<ThrottleLevel> throttleLevels) {

    public FrontierSettings {
        worldPolicies = Map.copyOf(Objects.requireNonNull(worldPolicies, "worldPolicies"));
        throttleLevels = List.copyOf(Objects.requireNonNull(throttleLevels, "throttleLevels"));
        if (worldPolicies.isEmpty()) {
            throw new IllegalArgumentException("At least one managed world must be enabled");
        }
        if (protectionRadiusChunks < 0 || protectionRadiusChunks > 32) {
            throw new IllegalArgumentException("tracking.protection-radius-chunks must be within 0..32");
        }
        if (mutationQueueCapacity < 128) {
            throw new IllegalArgumentException("tracking.mutation-queue-capacity must be >= 128");
        }
        if (mutationBatchSize < 1 || mutationBatchSize > mutationQueueCapacity) {
            throw new IllegalArgumentException("tracking.mutation-batch-size must be within 1..queue capacity");
        }
        if (throttleSamplePeriodTicks < 1) {
            throw new IllegalArgumentException("throttle.sample-period-ticks must be >= 1");
        }
    }

    public static FrontierSettings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        ConfigurationSection worlds = requireSection(config, "worlds");
        Map<String, CoreBoundaryPolicy> policies = new LinkedHashMap<>();
        for (String worldName : worlds.getKeys(false)) {
            String base = "worlds." + worldName;
            if (!config.getBoolean(base + ".enabled", false)) {
                continue;
            }
            int radius = config.getInt(base + ".core-radius-blocks", -1);
            if (radius < 0) {
                throw new IllegalArgumentException(base + ".core-radius-blocks must be >= 0");
            }
            policies.put(worldName, new CoreBoundaryPolicy(radius));
        }

        int protectionRadius = config.getInt("tracking.protection-radius-chunks", 2);
        int queueCapacity = config.getInt("tracking.mutation-queue-capacity", 65536);
        int batchSize = config.getInt("tracking.mutation-batch-size", 512);
        boolean throttleRequired = config.getBoolean("throttle.require-supported-adapter", true);
        long samplePeriod = config.getLong("throttle.sample-period-ticks", 20L);
        double hysteresis = config.getDouble("throttle.recovery-hysteresis-mspt", 2.0);

        List<ThrottleLevel> levels = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("throttle.levels")) {
            String name = requiredString(entry, "name");
            double enterMspt = requiredNumber(entry, "enter-mspt").doubleValue();
            double maxRate = requiredNumber(entry, "max-generation-rate").doubleValue();
            int concurrent = requiredNumber(entry, "max-concurrent-generations").intValue();
            levels.add(new ThrottleLevel(name, enterMspt, new GenerationLimits(maxRate, concurrent)));
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("throttle.levels must contain at least one level");
        }

        return new FrontierSettings(
                policies,
                protectionRadius,
                queueCapacity,
                batchSize,
                throttleRequired,
                samplePeriod,
                hysteresis,
                levels);
    }

    private static ConfigurationSection requireSection(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Missing configuration section: " + path);
        }
        return section;
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing throttle level field: " + key);
        }
        return value.toString();
    }

    private static Number requiredNumber(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Throttle level field must be numeric: " + key);
        }
        return number;
    }
}
