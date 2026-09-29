package net.enthusia.frontier.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded rolling evidence window with idle/generation-active and view-distance correlations. */
public final class PerformanceEvidenceWindow {
    private final int capacity;
    private final ArrayDeque<PerformanceSample> samples = new ArrayDeque<>();

    public PerformanceEvidenceWindow(int capacity) {
        if (capacity < 12) {
            throw new IllegalArgumentException("capacity must be >= 12");
        }
        this.capacity = capacity;
    }

    public synchronized void record(PerformanceSample sample) {
        samples.addLast(Objects.requireNonNull(sample, "sample"));
        while (samples.size() > capacity) {
            samples.removeFirst();
        }
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized Summary summary() {
        return summarize(List.copyOf(samples));
    }

    public synchronized List<ViewDistanceBucket> viewDistanceBuckets() {
        Map<Integer, List<PerformanceSample>> grouped = new LinkedHashMap<>();
        for (PerformanceSample sample : samples) {
            int bucket = sample.targetViewDistance() > 0
                    ? sample.targetViewDistance()
                    : sample.maxViewDistance();
            grouped.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(sample);
        }

        List<ViewDistanceBucket> result = new ArrayList<>();
        for (Map.Entry<Integer, List<PerformanceSample>> entry : grouped.entrySet()) {
            Summary summary = summarize(entry.getValue());
            result.add(new ViewDistanceBucket(
                    entry.getKey(),
                    summary.samples(),
                    summary.generationActiveSamples(),
                    summary.averageMspt(),
                    summary.idleAverageMspt(),
                    summary.generationActiveAverageMspt(),
                    summary.correlatedGenerationDeltaMspt(),
                    summary.averageGeneratedChunksPerSecond(),
                    summary.averageTpsOneMinute()));
        }
        result.sort(Comparator.comparingInt(ViewDistanceBucket::viewDistance).reversed());
        return List.copyOf(result);
    }

    private static Summary summarize(List<PerformanceSample> source) {
        if (source.isEmpty()) {
            return new Summary(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, Double.NaN, 0, Double.NaN, Double.NaN);
        }

        double totalMspt = 0.0;
        double totalTps = 0.0;
        double totalGenerationRate = 0.0;
        double totalViewDistance = 0.0;
        double totalLoadedChunks = 0.0;
        int maxViewDistance = 0;
        int activeCount = 0;
        int idleCount = 0;
        double activeMspt = 0.0;
        double idleMspt = 0.0;

        for (PerformanceSample sample : source) {
            totalMspt += sample.mspt();
            totalTps += sample.tpsOneMinute();
            totalGenerationRate += sample.generatedChunksPerSecond();
            totalViewDistance += sample.averageViewDistance();
            totalLoadedChunks += sample.loadedChunks();
            maxViewDistance = Math.max(maxViewDistance, sample.maxViewDistance());
            if (sample.generationActive()) {
                activeCount++;
                activeMspt += sample.mspt();
            } else {
                idleCount++;
                idleMspt += sample.mspt();
            }
        }

        double activeAverage = activeCount == 0 ? Double.NaN : activeMspt / activeCount;
        double idleAverage = idleCount == 0 ? Double.NaN : idleMspt / idleCount;
        double delta = Double.isFinite(activeAverage) && Double.isFinite(idleAverage)
                ? activeAverage - idleAverage
                : Double.NaN;
        int count = source.size();
        return new Summary(
                count,
                activeCount,
                totalMspt / count,
                idleAverage,
                activeAverage,
                delta,
                totalGenerationRate / count,
                totalViewDistance / count,
                maxViewDistance,
                totalTps / count,
                totalLoadedChunks / count);
    }

    public record Summary(
            int samples,
            int generationActiveSamples,
            double averageMspt,
            double idleAverageMspt,
            double generationActiveAverageMspt,
            double correlatedGenerationDeltaMspt,
            double averageGeneratedChunksPerSecond,
            double averageViewDistance,
            int maxViewDistance,
            double averageTpsOneMinute,
            double averageLoadedChunks) {
    }

    public record ViewDistanceBucket(
            int viewDistance,
            int samples,
            int generationActiveSamples,
            double averageMspt,
            double idleAverageMspt,
            double generationActiveAverageMspt,
            double correlatedGenerationDeltaMspt,
            double averageGeneratedChunksPerSecond,
            double averageTpsOneMinute) {
    }
}
