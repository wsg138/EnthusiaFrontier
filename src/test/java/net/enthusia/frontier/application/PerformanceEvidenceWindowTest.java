package net.enthusia.frontier.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceEvidenceWindowTest {
    @Test
    void separatesIdleAndGenerationMsptAndGroupsByViewDistance() {
        PerformanceEvidenceWindow window = new PerformanceEvidenceWindow(12);
        window.record(sample(10.0, 20.0, 15, 0, 0.0));
        window.record(sample(20.0, 20.0, 15, 10, 2.0));
        window.record(sample(15.0, 20.0, 7, 0, 0.0));
        window.record(sample(25.0, 19.8, 7, 15, 3.0));

        PerformanceEvidenceWindow.Summary summary = window.summary();
        assertEquals(4, summary.samples());
        assertEquals(2, summary.generationActiveSamples());
        assertEquals(17.5, summary.averageMspt(), 1.0e-9);
        assertEquals(12.5, summary.idleAverageMspt(), 1.0e-9);
        assertEquals(22.5, summary.generationActiveAverageMspt(), 1.0e-9);
        assertEquals(10.0, summary.correlatedGenerationDeltaMspt(), 1.0e-9);

        List<PerformanceEvidenceWindow.ViewDistanceBucket> buckets = window.viewDistanceBuckets();
        assertEquals(2, buckets.size());
        assertEquals(15, buckets.get(0).viewDistance());
        assertEquals(10.0, buckets.get(0).correlatedGenerationDeltaMspt(), 1.0e-9);
        assertEquals(7, buckets.get(1).viewDistance());
        assertEquals(10.0, buckets.get(1).correlatedGenerationDeltaMspt(), 1.0e-9);
    }

    @Test
    void rollingWindowDropsOldestSamplesAtCapacity() {
        PerformanceEvidenceWindow window = new PerformanceEvidenceWindow(12);
        for (int index = 0; index < 14; index++) {
            window.record(sample(index, 20.0, 9, 0, 0.0));
        }
        assertEquals(12, window.size());
        assertEquals(7.5, window.summary().averageMspt(), 1.0e-9);
    }

    private static PerformanceSample sample(
            double mspt,
            double tps,
            int targetView,
            long generatedChunks,
            double generatedPerSecond) {
        return new PerformanceSample(
                1L,
                mspt,
                tps,
                1,
                targetView,
                targetView,
                targetView,
                targetView,
                7.0,
                targetView * targetView,
                generatedChunks,
                generatedPerSecond,
                0,
                0,
                0.0,
                generatedChunks > 0 ? "generating" : "idle",
                "test",
                targetView);
    }
}
