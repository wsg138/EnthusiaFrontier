package net.enthusia.frontier.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.enthusia.frontier.application.PerformanceSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceEvidenceCsvWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsHeaderOnceAndSanitizesBands() throws Exception {
        Path path = tempDir.resolve("nested/performance.csv");
        PerformanceEvidenceCsvWriter writer = new PerformanceEvidenceCsvWriter(path);
        PerformanceSample sample = new PerformanceSample(
                123L, 12.5, 20.0, 2, 15.0, 15, 15.0, 15, 7.0, 800,
                5L, 2.0, 1, 2, 0.5, "healthy,gen", "healthy\nview", 15);

        writer.append(sample);
        writer.append(sample);

        List<String> lines = Files.readAllLines(path);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("epoch_ms,mspt,tps_1m"));
        assertTrue(lines.get(1).contains("healthy_gen,healthy_view,15"));
        assertEquals(lines.get(1), lines.get(2));
    }
}
