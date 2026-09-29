package net.enthusia.frontier.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import net.enthusia.frontier.application.PerformanceSample;

/** Append-only CSV evidence log written away from the server tick thread. */
public final class PerformanceEvidenceCsvWriter {
    private static final String HEADER = "epoch_ms,mspt,tps_1m,players,avg_view,max_view,avg_send,max_send,avg_sim,"
            + "loaded_chunks,generated_chunks,generated_per_sec,shield_queued,shield_in_flight,shield_debt,"
            + "generation_band,view_band,target_view\n";

    private final Path path;

    public PerformanceEvidenceCsvWriter(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public synchronized void append(PerformanceSample sample) throws IOException {
        Objects.requireNonNull(sample, "sample");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(path) || Files.size(path) == 0L) {
            Files.writeString(path, HEADER, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(path, format(sample), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String format(PerformanceSample sample) {
        return String.format(Locale.ROOT,
                "%d,%.3f,%.3f,%d,%.3f,%d,%.3f,%d,%.3f,%d,%d,%.3f,%d,%d,%.3f,%s,%s,%d%n",
                sample.epochMillis(),
                sample.mspt(),
                sample.tpsOneMinute(),
                sample.onlinePlayers(),
                sample.averageViewDistance(),
                sample.maxViewDistance(),
                sample.averageSendViewDistance(),
                sample.maxSendViewDistance(),
                sample.averageSimulationDistance(),
                sample.loadedChunks(),
                sample.generatedChunks(),
                sample.generatedChunksPerSecond(),
                sample.shieldQueued(),
                sample.shieldInFlight(),
                sample.shieldDebtChunks(),
                sanitize(sample.generationBand()),
                sanitize(sample.viewDistanceBand()),
                sample.targetViewDistance());
    }

    private static String sanitize(String value) {
        return value.replace(',', '_').replace('\n', '_').replace('\r', '_');
    }
}
