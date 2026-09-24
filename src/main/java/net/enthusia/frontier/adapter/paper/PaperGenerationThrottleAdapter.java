package net.enthusia.frontier.adapter.paper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.enthusia.frontier.application.GenerationThrottlePort;
import net.enthusia.frontier.domain.GenerationLimits;

/**
 * Narrow reflective adapter for Paper/Leaf runtime generation controls.
 * All unstable server-internal names are intentionally isolated in this class.
 */
public final class PaperGenerationThrottleAdapter implements GenerationThrottlePort {
    private static final String GLOBAL_CONFIGURATION = "io.papermc.paper.configuration.GlobalConfiguration";

    private final Object basicConfiguration;
    private final Object advancedConfiguration;
    private final Field generationRateField;
    private final Field concurrentGenerationsField;
    private final double originalGenerationRate;
    private final int originalConcurrentGenerations;
    private boolean restored;

    private PaperGenerationThrottleAdapter(
            Object basicConfiguration,
            Object advancedConfiguration,
            Field generationRateField,
            Field concurrentGenerationsField) throws IllegalAccessException {
        this.basicConfiguration = basicConfiguration;
        this.advancedConfiguration = advancedConfiguration;
        this.generationRateField = generationRateField;
        this.concurrentGenerationsField = concurrentGenerationsField;
        this.originalGenerationRate = generationRateField.getDouble(basicConfiguration);
        this.originalConcurrentGenerations = concurrentGenerationsField.getInt(advancedConfiguration);
    }

    public static PaperGenerationThrottleAdapter create() throws ReflectiveOperationException {
        Class<?> configurationClass = Class.forName(GLOBAL_CONFIGURATION);
        Method get = configurationClass.getMethod("get");
        Object configuration = get.invoke(null);
        if (configuration == null) {
            throw new IllegalStateException("Paper GlobalConfiguration is not initialized");
        }

        Field basicField = configurationClass.getField("chunkLoadingBasic");
        Field advancedField = configurationClass.getField("chunkLoadingAdvanced");
        Object basic = basicField.get(configuration);
        Object advanced = advancedField.get(configuration);
        if (basic == null || advanced == null) {
            throw new IllegalStateException("Paper chunk-loading configuration is not initialized");
        }

        Field generationRate = basic.getClass().getField("playerMaxChunkGenerateRate");
        Field concurrentGenerations = advanced.getClass().getField("playerMaxConcurrentChunkGenerates");
        return new PaperGenerationThrottleAdapter(basic, advanced, generationRate, concurrentGenerations);
    }

    @Override
    public synchronized void apply(GenerationLimits limits) throws IllegalAccessException {
        generationRateField.setDouble(basicConfiguration, limits.maxGenerateRate());
        concurrentGenerationsField.setInt(advancedConfiguration, limits.maxConcurrentGenerations());
        restored = false;
    }

    @Override
    public synchronized void restore() throws IllegalAccessException {
        if (restored) {
            return;
        }
        generationRateField.setDouble(basicConfiguration, originalGenerationRate);
        concurrentGenerationsField.setInt(advancedConfiguration, originalConcurrentGenerations);
        restored = true;
    }

    public double originalGenerationRate() {
        return originalGenerationRate;
    }

    public int originalConcurrentGenerations() {
        return originalConcurrentGenerations;
    }
}
