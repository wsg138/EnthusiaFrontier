package net.enthusia.frontier.adapter.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.enthusia.frontier.domain.GenerationLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PaperGenerationThrottleAdapterTest {
    @AfterEach
    void resetConfiguration() {
        GlobalConfiguration.set(new GlobalConfiguration());
    }

    @Test
    void appliesLimitsAndRestoresOriginalPaperValues() throws Exception {
        GlobalConfiguration configuration = new GlobalConfiguration();
        configuration.chunkLoadingBasic.playerMaxChunkGenerateRate = 75.0;
        configuration.chunkLoadingAdvanced.playerMaxConcurrentChunkGenerates = 6;
        GlobalConfiguration.set(configuration);

        PaperGenerationThrottleAdapter adapter = PaperGenerationThrottleAdapter.create();
        assertEquals(75.0, adapter.originalGenerationRate());
        assertEquals(6, adapter.originalConcurrentGenerations());

        adapter.apply(new GenerationLimits(8.5, 2));
        assertEquals(8.5, configuration.chunkLoadingBasic.playerMaxChunkGenerateRate);
        assertEquals(2, configuration.chunkLoadingAdvanced.playerMaxConcurrentChunkGenerates);

        adapter.restore();
        assertEquals(75.0, configuration.chunkLoadingBasic.playerMaxChunkGenerateRate);
        assertEquals(6, configuration.chunkLoadingAdvanced.playerMaxConcurrentChunkGenerates);
        adapter.restore();
        assertEquals(75.0, configuration.chunkLoadingBasic.playerMaxChunkGenerateRate);
    }

    @Test
    void createFailsClosedWhenPaperConfigurationIsUnavailable() {
        GlobalConfiguration.set(null);
        assertThrows(IllegalStateException.class, PaperGenerationThrottleAdapter::create);

        GlobalConfiguration missingBasic = new GlobalConfiguration();
        missingBasic.chunkLoadingBasic = null;
        GlobalConfiguration.set(missingBasic);
        assertThrows(IllegalStateException.class, PaperGenerationThrottleAdapter::create);

        GlobalConfiguration missingAdvanced = new GlobalConfiguration();
        missingAdvanced.chunkLoadingAdvanced = null;
        GlobalConfiguration.set(missingAdvanced);
        assertThrows(IllegalStateException.class, PaperGenerationThrottleAdapter::create);
    }
}
