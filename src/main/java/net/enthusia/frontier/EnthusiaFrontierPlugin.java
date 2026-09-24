package net.enthusia.frontier;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.enthusia.frontier.adapter.bukkit.FrontierListener;
import net.enthusia.frontier.adapter.paper.PaperGenerationThrottleAdapter;
import net.enthusia.frontier.adapter.persistence.FileSafetyLatch;
import net.enthusia.frontier.adapter.persistence.SqliteFrontierRepository;
import net.enthusia.frontier.adapter.simulation.SimulationGenerationThrottleAdapter;
import net.enthusia.frontier.application.AdaptiveThrottleService;
import net.enthusia.frontier.application.FrontierStats;
import net.enthusia.frontier.application.FrontierTrackingService;
import net.enthusia.frontier.application.GenerationThrottlePort;
import net.enthusia.frontier.application.MutationJournal;
import net.enthusia.frontier.application.SafetyLatch;
import net.enthusia.frontier.application.ServerPerformancePort;
import net.enthusia.frontier.config.FrontierSettings;
import net.enthusia.frontier.domain.AdaptiveThrottlePolicy;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.enthusia.frontier.domain.ThrottleLevel;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit/Paper composition root for the Frontier hexagonal application. */
public final class EnthusiaFrontierPlugin extends JavaPlugin {
    private static final String MOCKBUKKIT_PACKAGE_PREFIX = "org.mockbukkit.";
    private static final double SIMULATED_MSPT = 20.0;

    private FrontierSettings settings;
    private SqliteFrontierRepository repository;
    private SafetyLatch safetyLatch;
    private MutationJournal mutationJournal;
    private AdaptiveThrottleService throttleService;
    private GenerationThrottlePort throttlePort;
    private BukkitTask throttleTask;
    private String throttleAdapterMode = "uninitialized";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = FrontierSettings.load(getConfig());
            Path dataFolder = getDataFolder().toPath();
            repository = new SqliteFrontierRepository(dataFolder.resolve("frontier.db"));
            repository.initialize();
            safetyLatch = new FileSafetyLatch(
                    dataFolder.resolve("CLEANUP_UNSAFE.latch"),
                    message -> getLogger().severe(message));
            mutationJournal = new MutationJournal(
                    repository,
                    safetyLatch,
                    settings.mutationQueueCapacity(),
                    settings.mutationBatchSize(),
                    message -> getLogger().severe(message));
            mutationJournal.start();

            FrontierTrackingService tracking = new FrontierTrackingService(
                    settings.worldPolicies(),
                    settings.protectionRadiusChunks(),
                    mutationJournal,
                    Clock.systemUTC());
            getServer().getPluginManager().registerEvents(new FrontierListener(tracking), this);

            initializeThrottle();
            registerCommand();
            getLogger().info("EnthusiaFrontier enabled for " + settings.worldPolicies().size()
                    + " world(s); cleanup remains fail-closed and disabled by initial milestone.");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "EnthusiaFrontier failed safe during startup", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (throttleTask != null) {
            throttleTask.cancel();
            throttleTask = null;
        }
        if (throttleService != null) {
            try {
                throttleService.restore();
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Failed to restore original generation limits", exception);
            }
        }
        if (mutationJournal != null) {
            mutationJournal.close();
        }
        if (repository != null) {
            try {
                repository.close();
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Failed to close Frontier ledger", exception);
            }
        }
    }

    private void initializeThrottle() throws Exception {
        ServerPerformancePort performancePort;
        if (isMockBukkitRuntime()) {
            throttlePort = new SimulationGenerationThrottleAdapter();
            throttleAdapterMode = "simulation";
            performancePort = () -> SIMULATED_MSPT;
            getLogger().info("MockBukkit runtime detected; using simulation-only throttle adapter. "
                    + "This does not validate Paper/Leaf chunk-generation internals.");
        } else {
            try {
                throttlePort = PaperGenerationThrottleAdapter.create();
                throttleAdapterMode = "paper";
                performancePort = () -> getServer().getAverageTickTime();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throttleAdapterMode = "unsupported";
                if (settings.throttleRequired()) {
                    throw new IllegalStateException(
                            "This Paper/Leaf build does not expose Frontier's validated generation-throttle adapter",
                            exception);
                }
                getLogger().log(Level.WARNING,
                        "Generation throttle adapter unavailable; tracking will continue because throttle.require-supported-adapter=false",
                        exception);
                return;
            }
        }

        AdaptiveThrottlePolicy policy = new AdaptiveThrottlePolicy(
                settings.throttleLevels(), settings.recoveryHysteresisMspt());
        throttleService = new AdaptiveThrottleService(
                policy,
                performancePort,
                Objects.requireNonNull(throttlePort));
        sampleThrottle();
        throttleTask = getServer().getScheduler().runTaskTimer(
                this,
                this::sampleThrottle,
                settings.throttleSamplePeriodTicks(),
                settings.throttleSamplePeriodTicks());
    }

    private boolean isMockBukkitRuntime() {
        return getServer().getClass().getName().startsWith(MOCKBUKKIT_PACKAGE_PREFIX);
    }

    private void sampleThrottle() {
        if (throttleService == null) {
            return;
        }
        try {
            throttleService.sample();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Adaptive generation throttle failed", exception);
            if (settings.throttleRequired()) {
                getServer().getPluginManager().disablePlugin(this);
            } else if (throttleTask != null) {
                throttleTask.cancel();
                throttleTask = null;
            }
        }
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("frontier"), "frontier command missing from plugin.yml");
        command.setExecutor((sender, ignoredCommand, ignoredLabel, args) -> {
            if (args.length != 0 && !args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§cUsage: /frontier status");
                return true;
            }
            for (String line : statusLines()) {
                sender.sendMessage(line);
            }
            return true;
        });
    }

    private List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§6[EnthusiaFrontier] §f" + getPluginMeta().getVersion());
        String worlds = settings.worldPolicies().entrySet().stream()
                .map(EnthusiaFrontierPlugin::formatWorldPolicy)
                .collect(Collectors.joining(", "));
        lines.add("§7managed worlds: §f" + worlds);

        ThrottleLevel level = throttleService == null ? null : throttleService.currentLevel();
        String throttleStatus = throttleAdapterMode.equals("unsupported")
                ? "unsupported"
                : level == null
                        ? "initializing"
                        : level.name() + " rate=" + level.limits().maxGenerateRate()
                                + "/s concurrent=" + level.limits().maxConcurrentGenerations();
        double mspt = throttleService == null ? 0.0 : throttleService.lastMspt();
        lines.add(String.format("§7MSPT: §f%.2f §7throttle: §f%s §7adapter: §f%s",
                mspt, throttleStatus, throttleAdapterMode));

        if (mutationJournal != null) {
            lines.add("§7ledger queue: §f" + mutationJournal.queueDepth()
                    + " §7healthy: §f" + mutationJournal.isHealthy());
        }
        if (safetyLatch != null) {
            lines.add("§7cleanup safety latch: §f" + (safetyLatch.isTripped() ? "TRIPPED" : "clear")
                    + (safetyLatch.isTripped() ? " §7(" + safetyLatch.reason() + ")" : ""));
        }
        if (repository != null) {
            try {
                FrontierStats stats = repository.stats();
                lines.add("§7chunks: temporary=§f" + stats.temporaryChunks()
                        + " §7protected=§f" + stats.protectedChunks()
                        + " §7deleted=§f" + stats.deletedChunks());
            } catch (SQLException exception) {
                lines.add("§7chunks: §cledger stats unavailable");
            }
        }
        lines.add("§7destructive cleanup: §cDISABLED §8(initial safety milestone)");
        return List.copyOf(lines);
    }

    private static String formatWorldPolicy(Map.Entry<String, CoreBoundaryPolicy> entry) {
        return entry.getKey() + "=" + entry.getValue().radiusBlocks();
    }
}
