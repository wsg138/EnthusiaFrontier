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
import net.enthusia.frontier.application.AdaptiveThrottleService;
import net.enthusia.frontier.application.AdaptiveViewDistanceService;
import net.enthusia.frontier.application.GenerationShieldController;
import net.enthusia.frontier.application.GenerationShieldMetrics;
import net.enthusia.frontier.application.GenerationShieldService;
import net.enthusia.frontier.application.PerformanceEvidenceWindow;
import net.enthusia.frontier.application.PerformanceSample;
import net.enthusia.frontier.config.PerformanceEvidenceSettings;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Low-frequency observer that correlates tick health with generation and view distance. */
public final class BukkitPerformanceEvidenceMonitor implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Server server;
    private final PerformanceEvidenceSettings settings;
    private final Set<String> managedWorlds;
    private final PerformanceEvidenceWindow window;
    private final PerformanceEvidenceCsvWriter csvWriter;
    private final Runnable sampler;
    private BukkitTask task;
    private long lastObservedGenerated;
    private long lastSampleNanos;
    private boolean baselineInitialized;
    private PerformanceSample latest;

    public BukkitPerformanceEvidenceMonitor(
            JavaPlugin plugin,
            PerformanceEvidenceSettings settings,
            Set<String> managedWorlds,
            GenerationShieldService generationShield,
            GenerationShieldController ignoredShieldController,
            AdaptiveThrottleService throttleService,
            AdaptiveViewDistanceService viewDistanceService,
            BukkitViewDistanceAdapter viewDistanceAdapter,
            Path csvPath) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.managedWorlds = Set.copyOf(Objects.requireNonNull(managedWorlds, "managedWorlds"));
        this.window = new PerformanceEvidenceWindow(settings.retentionSamples());
        this.csvWriter = new PerformanceEvidenceCsvWriter(Objects.requireNonNull(csvPath, "csvPath"));
        this.sampler = () -> sampleSnapshot(
                generationShield == null ? null : generationShield.metrics(),
                throttleService == null || throttleService.currentLevel() == null
                        ? "inactive"
                        : throttleService.currentLevel().name(),
                viewDistanceService == null || viewDistanceService.currentLevel() == null
                        ? "disabled"
                        : viewDistanceService.currentLevel().name(),
                viewDistanceAdapter == null ? 0 : viewDistanceAdapter.targetViewDistance());
    }

    public void start() {
        if (!settings.enabled() || task != null) {
            return;
        }
        task = server.getScheduler().runTaskTimer(
                plugin,
                sampler,
                settings.samplePeriodTicks(),
                settings.samplePeriodTicks());
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public PerformanceEvidenceWindow.Summary summary() {
        return window.summary();
    }

    public List<String> evidenceLines() {
        PerformanceEvidenceWindow.Summary summary = window.summary();
        List<String> lines = new ArrayList<>();
        lines.add("§8§m-----------------------------------------------------");
        lines.add("§6§lFRONTIER PERFORMANCE EVIDENCE");
        lines.add("§8§m-----------------------------------------------------");
        if (summary.samples() == 0) {
            lines.add("§7No performance samples have been collected yet.");
            lines.add("§8Samples are collected automatically while the server is running.");
            lines.add("§8§m-----------------------------------------------------");
            return List.copyOf(lines);
        }

        lines.add("§e§lOVERVIEW");
        lines.add("§7 Samples §8» §f" + summary.samples()
                + " §8│ §7Generation-active §8» §f" + summary.generationActiveSamples());
        lines.add("§7 Avg MSPT §8» §f" + format(summary.averageMspt())
                + " §8│ §7Avg TPS §8» §f" + format(summary.averageTpsOneMinute()));

        lines.add("");
        lines.add("§b§lGENERATION IMPACT");
        lines.add("§7 Idle MSPT §8» §a" + format(summary.idleAverageMspt())
                + " §8│ §7Generating §8» §e" + format(summary.generationActiveAverageMspt()));
        lines.add("§7 Correlated delta §8» " + deltaColor(summary.correlatedGenerationDeltaMspt())
                + formatSigned(summary.correlatedGenerationDeltaMspt()) + " ms");
        lines.add("§7 Generation rate §8» §f" + format(summary.averageGeneratedChunksPerSecond()) + "/s"
                + " §8│ §7Loaded chunks §8» §f" + format(summary.averageLoadedChunks()));

        lines.add("");
        lines.add("§d§lVIEW DISTANCE");
        lines.add("§7 Average §8» §b" + format(summary.averageViewDistance())
                + " §8│ §7Maximum §8» §b" + summary.maxViewDistance());
        for (PerformanceEvidenceWindow.ViewDistanceBucket bucket : window.viewDistanceBuckets()) {
            lines.add("§8 • §7View §b" + bucket.viewDistance()
                    + " §8│ §7MSPT §f" + format(bucket.averageMspt())
                    + " §8│ §7Gen Δ " + deltaColor(bucket.correlatedGenerationDeltaMspt())
                    + formatSigned(bucket.correlatedGenerationDeltaMspt())
                    + " §8│ §7Gen §f" + format(bucket.averageGeneratedChunksPerSecond()) + "/s"
                    + " §8│ §7n=§f" + bucket.samples());
        }

        lines.add("");
        lines.add("§8Correlation helps tune limits; it does not prove one subsystem caused every MSPT change.");
        lines.add("§8§m-----------------------------------------------------");
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

    private void sampleSnapshot(
            GenerationShieldMetrics metrics,
            String generationBand,
            String viewBand,
            int targetView) {
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

    private static String deltaColor(double value) {
        if (!Double.isFinite(value)) {
            return "§7";
        }
        if (value >= 5.0) {
            return "§c";
        }
        if (value >= 2.0) {
            return "§6";
        }
        if (value > 0.5) {
            return "§e";
        }
        return "§a";
    }
}
