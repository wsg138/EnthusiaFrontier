package net.enthusia.frontier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.enthusia.frontier.domain.GenerationShieldLevel;
import net.enthusia.frontier.domain.GlobalGenerationLimits;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated runtime settings for Frontier's true whole-server generation shield. */
public record GenerationShieldSettings(
        boolean enabled,
        int queueCapacity,
        int extraGuardRadiusChunks,
        int maxGuardRadiusChunks,
        int refreshChecksPerTick,
        long samplePeriodTicks,
        double recoveryHysteresisMspt,
        List<GenerationShieldLevel> levels) {

    public GenerationShieldSettings {
        Objects.requireNonNull(levels, "levels");
        levels = List.copyOf(levels);
        if (queueCapacity < 128 || queueCapacity > 1_000_000) {
            throw new IllegalArgumentException("generation shield queue capacity must be within 128..1000000");
        }
        if (extraGuardRadiusChunks < 1 || extraGuardRadiusChunks > 8) {
            throw new IllegalArgumentException("generation shield extra guard radius must be within 1..8 chunks");
        }
        if (maxGuardRadiusChunks < 4 || maxGuardRadiusChunks > 40
                || maxGuardRadiusChunks < extraGuardRadiusChunks) {
            throw new IllegalArgumentException("generation shield max guard radius must be within 4..40 chunks");
        }
        if (refreshChecksPerTick < 64 || refreshChecksPerTick > 65_536) {
            throw new IllegalArgumentException("generation shield refresh checks must be within 64..65536");
        }
        if (samplePeriodTicks < 1) {
            throw new IllegalArgumentException("generation shield sample period must be >= 1 tick");
        }
        if (!Double.isFinite(recoveryHysteresisMspt) || recoveryHysteresisMspt < 0.0) {
            throw new IllegalArgumentException("generation shield hysteresis must be non-negative and finite");
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("generation shield requires at least one level");
        }
    }

    public static GenerationShieldSettings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        List<GenerationShieldLevel> levels = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("generation-shield.levels")) {
            String name = requiredString(entry, "name");
            double enterMspt = requiredNumber(entry, "enter-mspt").doubleValue();
            double rate = requiredNumber(entry, "chunks-per-second").doubleValue();
            int concurrent = requiredNumber(entry, "max-concurrent").intValue();
            levels.add(new GenerationShieldLevel(name, enterMspt, new GlobalGenerationLimits(rate, concurrent)));
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("generation-shield.levels must contain at least one level");
        }
        return new GenerationShieldSettings(
                config.getBoolean("generation-shield.enabled", true),
                config.getInt("generation-shield.queue-capacity", 65_536),
                config.getInt("generation-shield.extra-guard-radius-chunks", 2),
                config.getInt("generation-shield.max-guard-radius-chunks", 36),
                config.getInt("generation-shield.refresh-checks-per-tick", 4096),
                config.getLong("generation-shield.sample-period-ticks", 20L),
                config.getDouble("generation-shield.recovery-hysteresis-mspt", 2.0),
                levels);
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing generation shield level field: " + key);
        }
        return value.toString();
    }

    private static Number requiredNumber(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Generation shield level field must be numeric: " + key);
        }
        return number;
    }
}
