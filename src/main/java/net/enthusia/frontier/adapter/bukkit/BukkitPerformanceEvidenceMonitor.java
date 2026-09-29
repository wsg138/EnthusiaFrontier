package net.enthusia.frontier.adapter.bukkit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import net.enthusia.frontier.adapter.persistence.PerformanceEvidenceCsvWriter;
import net.enthusia.frontier.application.GenerationShieldMetrics;
import net.enthusia.frontier.application.PerformanceEvidenceWindow;
import net.enthusia.frontier.application.PerformanceSample;
import net.enthusia.frontier.config.PerformanceEvidenceSettings;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Low-frequency observer that correlates tick health with generation and view distance. */
public final class BukkitPerformanceEvidenceMonitor {
    private final JavaPlugin plugin;
    private final Server server;
    private final PerformanceEvidenceSettings settings;
    private final Set<String> managedWorlds;
    private final PerformanceEvidenceWindow window;
    private final PerformanceEvidenceCsvWriter csvWriter;
    private long lastObservedGenerated;
    private long lastSampleNanos;
    private boolean baselineInitialized;
    private PerformanceSample latest;

    public BukkitPerformanceEvidenceMonitor(
            JavaPlugin plugin,
            PerformanceEvidenceSettings settings,
            Set<String> managedWorlds,
            Path csvPath) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.managedWorlds = Set.copyOf(Objects.requireNonNull(managedWorlds, "managedWorlds"));
        this.window = new PerformanceEvidenceWindow(settings.retentionSamples());
        this.csvWriter = new PerformanceEvidenceCsvWriter(Objects.requireNonNull(csvPath, "csvPath"));
    }

    /**
     * Records one immutable operational snapshot. Mutable generation/controller services are
     * deliberately owned by the plugin composition root and are never retained here.
     */
    public void sample(
            GenerationShieldMetrics metrics,
            String generationBand,
            String viewBand,
            int targetView) {
        Objects.requireNonNull(generationBand, "generationBand");
        Objects.requireNonNull(viewBand, "viewBand");

        long nowNanos = System.nanoTime();
        long observedGenerated = metrics == null ? lastObservedGenerated : metrics.observedGenerated();
        if (!baselineInitialized) {
            lastObservedGenerated = observedGenerated;
            lastSampleNanos = nowNanos;
            baselineInitialized = true;
            return;
        }
        double elapsedSeconds = Math.max(0.001, (nowNanos - lastSampleNanos) / 1_000_000_000.0);
        lastSampleNanos = nowNanos;
        long generatedDelta = Math.max(0L, observedGenerated - lastObservedGenerated);
        lastObservedGenerated = observedGenerated;

        Collection<? extends Player> players = server.getOnlinePlayers();
        double totalView = 0.0;
        double totalSend = 0.0;
        double totalSimulation = 0.0;
        int maxView = 0;
        int maxSend = 0;
        for (Player player : players) {
            int view = player.getViewDistance();
            int send = player.getSendViewDistance();
            totalView += view;
            totalSend += send;
            totalSimulation += player.getSimulationDistance();
            maxView = Math.max(maxView, view);
            maxSend = Math.max(maxSend, send);
        }
        int playerCount = players.size();
        double averageView = playerCount == 0 ? 0.0 : totalView / playerCount;
        double averageSend = playerCount == 0 ? 0.0 : totalSend / playerCount;
        double averageSimulation = playerCount == 0 ? 0.0 : totalSimulation / playerCount;

        int loadedChunks = 0;
        for (String worldName : managedWorlds) {
            World world = server.getWorld(worldName);
            if (world != null) {
                loadedChunks += world.getLoadedChunks().length;
            }
        }

        double[] tps = server.getTPS();
        PerformanceSample sample = new PerformanceSample(
                System.currentTimeMillis(),
                server.getAverageTickTime(),
                tps.length == 0 ? 20.0 : tps[0],
                playerCount,
                averageView,
                maxView,
                averageSend,
                maxSend,
                averageSimulation,
                loadedChunks,
                generatedDelta,
                generatedDelta / elapsedSeconds,
                metrics == null ? 0 : metrics.queued(),
                metrics == null ? 0 : metrics.inFlight(),
                metrics == null ? 0.0 : metrics.observedDebtChunks(),
                generationBand,
                viewBand,
                targetView);
        latest = sample;
        window.record(sample);
        if (settings.csvEnabled()) {
            server.getScheduler().runTaskAsynchronously(plugin, () -> appendCsv(sample));
        }
    }

    public PerformanceEvidenceWindow.Summary summary() {
        return window.summary();
    }

    public List<String> evidenceLines() {
        PerformanceEvidenceWindow.Summary summary = window.summary();
        List<String> lines = new ArrayList<>();
        lines.add("§6[Frontier evidence] §7samples=§f" + summary.samples()
                + " §7generation-active=§f" + summary.generationActiveSamples());
        if (summary.samples() == 0) {
            lines.add("§7No evidence samples collected yet.");
            return List.copyOf(lines);
        }
        lines.add("§7MSPT avg=§f" + format(summary.averageMspt())
                + " §7idle=§f" + format(summary.idleAverageMspt())
                + " §7generating=§f" + format(summary.generationActiveAverageMspt())
                + " §7correlated generation delta=§f" + formatSigned(summary.correlatedGenerationDeltaMspt()));
        lines.add("§7TPS(1m) avg=§f" + format(summary.averageTpsOneMinute())
                + " §7generated=§f" + format(summary.averageGeneratedChunksPerSecond()) + "/s"
                + " §7view avg/max=§f" + format(summary.averageViewDistance()) + "/" + summary.maxViewDistance()
                + " §7loaded chunks avg=§f" + format(summary.averageLoadedChunks()));
        for (PerformanceEvidenceWindow.ViewDistanceBucket bucket : window.viewDistanceBuckets()) {
            lines.add("§7view §f" + bucket.viewDistance()
                    + " §7samples=§f" + bucket.samples()
                    + " §7MSPT=§f" + format(bucket.averageMspt())
                    + " §7idle/gen=§f" + format(bucket.idleAverageMspt()) + "/"
                    + format(bucket.generationActiveAverageMspt())
                    + " §7delta=§f" + formatSigned(bucket.correlatedGenerationDeltaMspt())
                    + " §7gen=§f" + format(bucket.averageGeneratedChunksPerSecond()) + "/s");
        }
        lines.add("§8Correlation is evidence for tuning, not proof that one subsystem caused all MSPT change.");
        return List.copyOf(lines);
    }

    public String statusText() {
        PerformanceEvidenceWindow.Summary summary = window.summary();
        if (summary.samples() == 0) {
            return "collecting";
        }
        return "samples=" + summary.samples()
                + " mspt=" + format(summary.averageMspt())
                + " gen-delta=" + formatSigned(summary.correlatedGenerationDeltaMspt());
    }

    public PerformanceSample latest() {
        return latest;
    }

    private void appendCsv(PerformanceSample sample) {
        try {
            csvWriter.append(sample);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not append Frontier performance evidence CSV", exception);
        }
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "n/a";
    }

    private static String formatSigned(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%+.2f", value) : "n/a";
    }
}
