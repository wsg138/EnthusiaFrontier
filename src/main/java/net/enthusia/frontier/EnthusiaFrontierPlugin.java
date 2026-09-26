package net.enthusia.frontier;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.enthusia.frontier.adapter.bukkit.BukkitCleanupCoordinator;
import net.enthusia.frontier.adapter.bukkit.BukkitCleanupEnvironmentAdapter;
import net.enthusia.frontier.adapter.bukkit.FrontierAcceptanceHarness;
import net.enthusia.frontier.adapter.bukkit.FrontierListener;
import net.enthusia.frontier.adapter.bukkit.GenerationShieldMovementListener;
import net.enthusia.frontier.adapter.paper.MoonriseStorageReclaimAdapter;
import net.enthusia.frontier.adapter.paper.PaperChunkGenerationAdapter;
import net.enthusia.frontier.adapter.paper.PaperGenerationThrottleAdapter;
import net.enthusia.frontier.adapter.persistence.FileSafetyLatch;
import net.enthusia.frontier.adapter.persistence.SqliteFrontierRepository;
import net.enthusia.frontier.adapter.persistence.SqliteGenerationReadinessAdapter;
import net.enthusia.frontier.adapter.simulation.SimulationGenerationThrottleAdapter;
import net.enthusia.frontier.application.AdaptiveThrottleService;
import net.enthusia.frontier.application.CleanupService;
import net.enthusia.frontier.application.FrontierStats;
import net.enthusia.frontier.application.FrontierTrackingService;
import net.enthusia.frontier.application.GenerationBufferCoordinator;
import net.enthusia.frontier.application.GenerationShieldController;
import net.enthusia.frontier.application.GenerationShieldMetrics;
import net.enthusia.frontier.application.GenerationShieldService;
import net.enthusia.frontier.application.GenerationThrottlePort;
import net.enthusia.frontier.application.MutationJournal;
import net.enthusia.frontier.application.SafetyLatch;
import net.enthusia.frontier.application.ServerPerformancePort;
import net.enthusia.frontier.application.StorageReclaimPort;
import net.enthusia.frontier.config.FrontierSettings;
import net.enthusia.frontier.config.GenerationShieldSettings;
import net.enthusia.frontier.domain.AdaptiveThrottlePolicy;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.enthusia.frontier.domain.GenerationShieldLevel;
import net.enthusia.frontier.domain.GenerationShieldPolicy;
import net.enthusia.frontier.domain.ThrottleLevel;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit/Paper composition root for the Frontier hexagonal application. */
public final class EnthusiaFrontierPlugin extends JavaPlugin {
    private static final String MOCKBUKKIT_PACKAGE_PREFIX = "org.mockbukkit.";
    private static final double SIMULATED_MSPT = 20.0;

    private FrontierSettings settings;
    private GenerationShieldSettings shieldSettings;
    private SqliteFrontierRepository repository;
    private SafetyLatch safetyLatch;
    private MutationJournal mutationJournal;
    private AdaptiveThrottleService throttleService;
    private GenerationThrottlePort throttlePort;
    private BukkitTask throttleTask;
    private BukkitCleanupCoordinator cleanupCoordinator;
    private FrontierAcceptanceHarness acceptanceHarness;
    private SqliteGenerationReadinessAdapter generationReadiness;
    private GenerationShieldService generationShield;
    private GenerationShieldController generationShieldController;
    private GenerationBufferCoordinator generationBuffers;
    private BukkitTask generationPumpTask;
    private BukkitTask generationBufferTask;
    private BukkitTask generationSampleTask;
    private String throttleAdapterMode = "uninitialized";
    private String cleanupAdapterMode = "inactive";
    private String generationShieldMode = "inactive";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = FrontierSettings.load(getConfig());
            shieldSettings = GenerationShieldSettings.load(getConfig());
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
            initializeGenerationShield(dataFolder);
            initializeCleanup(tracking);
            registerCommand();
            getLogger().info("EnthusiaFrontier enabled for " + settings.worldPolicies().size()
                    + " world(s); generation-shield=" + generationShieldMode
                    + "; cleanup=" + cleanupStatus() + ".");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "EnthusiaFrontier failed safe during startup", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        cancelTask(generationSampleTask);
        generationSampleTask = null;
        cancelTask(generationBufferTask);
        generationBufferTask = null;
        cancelTask(generationPumpTask);
        generationPumpTask = null;
        if (generationShield != null) {
            generationShield.close();
            generationShield = null;
        }
        if (cleanupCoordinator != null) {
            cleanupCoordinator.close();
            cleanupCoordinator = null;
        }
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
        if (generationReadiness != null) {
            try {
                generationReadiness.close();
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Failed to close generation readiness ledger", exception);
            }
            generationReadiness = null;
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
                throttleAdapterMode = "paper-defense-in-depth";
                performancePort = () -> getServer().getAverageTickTime();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throttleAdapterMode = "unsupported";
                if (settings.throttleRequired()) {
                    throw new IllegalStateException(
                            "This Paper/Leaf build does not expose Frontier's validated generation-throttle adapter",
                            exception);
                }
                getLogger().log(Level.WARNING,
                        "Per-player Paper generation throttle unavailable; global Frontier shield remains primary",
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

    private void initializeGenerationShield(Path dataFolder) throws Exception {
        if (!shieldSettings.enabled()) {
            generationShieldMode = "disabled";
            return;
        }
        if (isMockBukkitRuntime()) {
            generationShieldMode = "simulation-unavailable";
            return;
        }

        Map<UUID, CoreBoundaryPolicy> runtimePolicies = new HashMap<>();
        List<ChunkKey> loadedManagedChunks = new ArrayList<>();
        for (Map.Entry<String, CoreBoundaryPolicy> entry : settings.worldPolicies().entrySet()) {
            World world = getServer().getWorld(entry.getKey());
            if (world == null) {
                throw new IllegalStateException("managed world is not loaded: " + entry.getKey());
            }
            UUID worldId = world.getUID();
            runtimePolicies.put(worldId, entry.getValue());
            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkKey key = new ChunkKey(worldId.toString(), chunk.getX(), chunk.getZ());
                if (entry.getValue().isManaged(key)) {
                    loadedManagedChunks.add(key);
                }
            }
        }

        generationReadiness = new SqliteGenerationReadinessAdapter(
                dataFolder.resolve("frontier.db"), safetyLatch, Clock.systemUTC());
        generationReadiness.initialize(runtimePolicies.keySet().stream().map(UUID::toString).toList());
        generationReadiness.adoptLoaded(loadedManagedChunks);

        generationShield = new GenerationShieldService(
                shieldSettings.queueCapacity(),
                key -> isManaged(runtimePolicies, key),
                generationReadiness,
                new PaperChunkGenerationAdapter(getServer()),
                () -> mutationJournal.isHealthy() && !safetyLatch.isTripped(),
                System::nanoTime,
                message -> {
                    safetyLatch.trip(message);
                    getLogger().severe(message);
                });
        GenerationShieldPolicy policy = new GenerationShieldPolicy(
                shieldSettings.levels(), shieldSettings.recoveryHysteresisMspt());
        generationShieldController = new GenerationShieldController(
                policy, () -> getServer().getAverageTickTime(), generationShield);
        generationShieldController.sample();
        generationBuffers = new GenerationBufferCoordinator(generationShield, generationReadiness);

        getServer().getPluginManager().registerEvents(
                new GenerationShieldMovementListener(
                        this, generationBuffers, shieldSettings, Set.copyOf(runtimePolicies.keySet())),
                this);

        generationPumpTask = getServer().getScheduler().runTaskTimer(
                this, generationShield::pump, 1L, 1L);
        generationBufferTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> generationBuffers.refresh(shieldSettings.refreshChecksPerTick()),
                1L,
                1L);
        generationSampleTask = getServer().getScheduler().runTaskTimer(
                this,
                this::sampleGenerationShield,
                shieldSettings.samplePeriodTicks(),
                shieldSettings.samplePeriodTicks());
        generationShieldMode = "global-paper-async";
    }

    private void initializeCleanup(FrontierTrackingService tracking) throws Exception {
        if (isMockBukkitRuntime()) {
            cleanupAdapterMode = "simulation-unavailable";
            if (settings.cleanup().enabled() && settings.cleanup().requireSupportedAdapter()) {
                throw new IllegalStateException("destructive cleanup cannot run in the MockBukkit simulation runtime");
            }
            return;
        }

        StorageReclaimPort storage;
        try {
            storage = MoonriseStorageReclaimAdapter.create();
            cleanupAdapterMode = storage.adapterName();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            cleanupAdapterMode = "unsupported";
            if (settings.cleanup().enabled() && settings.cleanup().requireSupportedAdapter()) {
                throw new IllegalStateException(
                        "This Leaf/Paper build does not expose Frontier's validated Moonrise storage adapter",
                        exception);
            }
            getLogger().log(Level.WARNING,
                    "Moonrise storage adapter unavailable; destructive cleanup will remain inactive", exception);
            return;
        }

        acceptanceHarness = new FrontierAcceptanceHarness(
                this, tracking, repository, mutationJournal, storage);
        if (!settings.cleanup().enabled()) {
            return;
        }
        if (settings.cleanup().physicalReclaim()
                && !storage.supportsPhysicalReclaim()
                && settings.cleanup().requireSupportedAdapter()) {
            throw new IllegalStateException("configured physical cleanup is unsupported by this storage adapter");
        }

        CleanupService cleanupService = new CleanupService(
                settings.cleanup(),
                tracking,
                mutationJournal,
                safetyLatch,
                new BukkitCleanupEnvironmentAdapter(getServer()),
                storage,
                Clock.systemUTC(),
                settings.cleanup().auditLog() ? getLogger()::info : ignored -> { },
                generationReadiness == null ? ignored -> { } : generationReadiness::forget);
        cleanupCoordinator = new BukkitCleanupCoordinator(
                this,
                settings.cleanup(),
                settings.worldPolicies().keySet(),
                repository,
                cleanupService,
                safetyLatch);
        cleanupCoordinator.start();
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

    private void sampleGenerationShield() {
        if (generationShieldController == null || generationShield == null) {
            return;
        }
        try {
            generationShieldController.sample();
        } catch (RuntimeException exception) {
            safetyLatch.trip("global generation shield MSPT controller failed");
            getLogger().log(Level.SEVERE, "Global generation shield failed closed", exception);
        }
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("frontier"), "frontier command missing from plugin.yml");
        command.setExecutor((sender, ignoredCommand, ignoredLabel, args) -> {
            if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("status"))) {
                for (String line : statusLines()) {
                    sender.sendMessage(line);
                }
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("acceptance")) {
                if (acceptanceHarness == null) {
                    sender.sendMessage("§cReal-server acceptance is unavailable on this runtime.");
                    return true;
                }
                return acceptanceHarness.execute(sender, args[1], args[2]);
            }
            sender.sendMessage("§cUsage: /frontier status");
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
        lines.add(String.format("§7MSPT: §f%.2f §7Paper per-player defense: §f%s §7adapter: §f%s",
                mspt, throttleStatus, throttleAdapterMode));

        if (generationShield != null) {
            GenerationShieldMetrics metrics = generationShield.metrics();
            GenerationShieldLevel shieldLevel = generationShieldController.currentLevel();
            String shieldLevelText = shieldLevel == null ? "initializing" : shieldLevel.name();
            lines.add("§7global shield: §f" + shieldLevelText
                    + " §7rate=§f" + generationShield.limits().chunksPerSecond() + "/s"
                    + " §7concurrent=§f" + generationShield.limits().maxConcurrent()
                    + " §7queued=§f" + metrics.queued()
                    + " §7in-flight=§f" + metrics.inFlight()
                    + " §7healthy=§f" + metrics.healthy());
            lines.add("§7shield throughput: started=§f" + metrics.started()
                    + " §7completed=§f" + metrics.completed()
                    + " §7deduped=§f" + metrics.deduplicated()
                    + " §7rejected=§f" + metrics.rejected()
                    + " §7pending buffers=§f" + generationBuffers.pendingBuffers()
                    + " §7ready cache=§f" + generationReadiness.cachedChunks());
        } else {
            lines.add("§7global shield: §f" + generationShieldMode);
        }

        if (mutationJournal != null) {
            lines.add("§7ledger queue: §f" + mutationJournal.queueDepth()
                    + " §7pending: §f" + mutationJournal.pendingMutations()
                    + " §7healthy: §f" + mutationJournal.isHealthy());
        }
        if (safetyLatch != null) {
            lines.add("§7safety latch: §f" + (safetyLatch.isTripped() ? "TRIPPED" : "clear")
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
        lines.add("§7cleanup: §f" + cleanupStatus() + " §7storage adapter: §f" + cleanupAdapterMode);
        lines.add("§7cleanup policy: retention=§f" + settings.cleanup().untouchedRetentionDays()
                + "d §7audit=§f" + (settings.cleanup().auditLog() ? "on" : "off"));
        return List.copyOf(lines);
    }

    private String cleanupStatus() {
        if (cleanupCoordinator != null) {
            return cleanupCoordinator.status();
        }
        if (settings == null || !settings.cleanup().enabled()) {
            return "disabled";
        }
        return "inactive";
    }

    private static boolean isManaged(Map<UUID, CoreBoundaryPolicy> policies, ChunkKey key) {
        try {
            CoreBoundaryPolicy policy = policies.get(UUID.fromString(key.worldUuid()));
            return policy != null && policy.isManaged(key);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static String formatWorldPolicy(Map.Entry<String, CoreBoundaryPolicy> entry) {
        return entry.getKey() + "=" + entry.getValue().radiusBlocks();
    }
}
