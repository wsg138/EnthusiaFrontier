package net.enthusia.frontier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class PluginMetadataVersionTest {
    @Test
    void processedPluginMetadataMatchesReleaseVersion() throws IOException {
        String expectedVersion = System.getProperty("frontier.expectedVersion");
        assertNotNull(expectedVersion, "build must provide the expected release version");

        try (InputStream input = PluginMetadataVersionTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(input, "processed plugin.yml must be present on the test classpath");
            String metadata = new String(input.readAllBytes(), UTF_8);
            assertTrue(
                    metadata.lines().anyMatch(line -> line.trim().equals("version: '" + expectedVersion + "'")),
                    "processed plugin.yml must use the Gradle releaseVersion");
            assertFalse(metadata.contains("${releaseVersion}"), "version placeholder must be expanded");
        }
    }
}
