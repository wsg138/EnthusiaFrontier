package net.enthusia.frontier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.enthusia.frontier.config.AdaptiveViewDistanceSettings;
import net.enthusia.frontier.config.FrontierSettings;
import net.enthusia.frontier.config.GenerationShieldSettings;
import net.enthusia.frontier.config.PerformanceEvidenceSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ReleaseProductionConfigTest {
    @Test
    void reviewedDayOneProfileLoadsWithIntendedProductionPolicy() throws Exception {
        String yaml = Files.readString(Path.of("release/frontier.day-one-production.yml"));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));

        FrontierSettings frontier = FrontierSettings.load(config);
        GenerationShieldSettings shield = GenerationShieldSettings.load(config);
        AdaptiveViewDistanceSettings viewDistance = AdaptiveViewDistanceSettings.load(config);
        PerformanceEvidenceSettings evidence = PerformanceEvidenceSettings.load(config);

        assertEquals(Set.of("world"), frontier.worldPolicies().keySet());
        assertEquals(100_000, frontier.worldPolicies().get("world").radiusBlocks());
        assertTrue(frontier.throttleRequired());
        assertTrue(shield.enabled());
        assertFalse(viewDistance.enabled());
        assertTrue(evidence.enabled());
        assertTrue(evidence.csvEnabled());

        assertTrue(frontier.cleanup().enabled());
        assertFalse(frontier.cleanup().dryRun());
        assertEquals(30L, frontier.cleanup().untouchedRetentionDays());
        assertFalse(frontier.cleanup().auditLog());
        assertTrue(frontier.cleanup().physicalReclaim());
        assertTrue(frontier.cleanup().requireSupportedAdapter());
    }
}
