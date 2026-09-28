package net.enthusia.frontier.adapter.bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.enthusia.frontier.application.GenerationAdmission;
import net.enthusia.frontier.application.GenerationShieldMetrics;
import net.enthusia.frontier.application.GenerationShieldService;
import net.enthusia.frontier.config.GenerationShieldSettings;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Isolated real-Leaf functional load proof for the aggregate generation shield.
 *
 * <p>This intentionally models requesters rather than pretending to be networked Minecraft
 * clients. The requests still flow through the production {@link GenerationShieldService},
 * the real Paper/Leaf async chunk-generation adapter, and durable readiness persistence.</p>
 */
public final class FrontierGenerationLoadHarness {
    public static final String TOKEN = "I_UNDERSTAND_DISPOSABLE_WORLD";
    private static final String SENTINEL_MOTD = "Enthusia Sentinel isolated smoke test";
    private static final int[] REQUESTER_COUNTS = {1, 10, 20, 40};
    private static final int REQUESTS_PER_CASE = 48;
    private static final int GRID_COLUMNS = 8;
    private static final int CHUNK_SPACING = 64;
    private static final int CORE_CLEARANCE_CHUNKS = 2048;
    private static final int CASE_SEPARATION_CHUNKS = 4096;
    private static final long CASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
    private static final long RATE_TOLERANCE_NANOS = TimeUnit.MILLISECONDS.toNanos(350);

    private final JavaPlugin plugin;
    private final GenerationShieldService shield;
    private final Map<UUID, CoreBoundaryPolicy> worldPolicies;
    private final double maximumConfiguredRate;
    private final int maximumConfiguredConcurrency;
    private boolean running;
    private BukkitTask monitorTask;

    public FrontierGenerationLoadHarness(
            JavaPlugin plugin,
            GenerationShieldService shield,
            GenerationShieldSettings settings,
            Map<UUID, CoreBoundaryPolicy> worldPolicies) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.shield = Objects.requireNonNull(shield, "shield");
        Objects.requireNonNull(settings, "settings");
        this.worldPolicies = Map.copyOf(Objects.requireNonNull(worldPolicies, "worldPolicies"));
        this.maximumConfiguredRate = settings.levels().stream()
                .mapToDouble(level -> level.limits().chunksPerSecond())
                .max()
                .orElseThrow();
        this.maximumConfiguredConcurrency = settings.levels().stream()
                .mapToInt(level -> level.limits().maxConcurrent())
                .max()
                .orElseThrow();
        if (maximumConfiguredRate <= 0.0 || maximumConfiguredConcurrency <= 0) {
            throw new IllegalArgumentException("generation load harness requires a positive configured generation budget");
        }
    }

    public boolean execute(CommandSender sender, String phase, String token) {
        Objects.requireNonNull(sender, "sender");
        if (!TOKEN.equals(token) || !isolatedSentinel(sender)) {
            sender.sendMessage("§cFrontier load testing is available only inside the isolated Sentinel sandbox.");
            return true;
        }
        if (!phase.equalsIgnoreCase("suite")) {
            sender.sendMessage("§cUsage: /frontier loadtest suite <confirmation-token>");
            return true;
        }
        if (running) {
            sender.sendMessage("§cA Frontier generation load suite is already running.");
            return true;
        }
        GenerationShieldMetrics initial = shield.metrics();
        if (!initial.healthy() || initial.queued() != 0 || initial.inFlight() != 0) {
            fail(sender, "generation shield is not healthy and idle before the load suite");
            return true;
        }
        World world = firstManagedWorld();
        if (world == null) {
            fail(sender, "no managed world is loaded for the generation load suite");
            return true;
        }

        running = true;
        emit(sender, "FRONTIER_GENERATION_LOAD_START cases=1,10,20,40 requests_per_case="
                + REQUESTS_PER_CASE
                + " configured_rate_upper_bound=" + maximumConfiguredRate
                + " configured_concurrency_upper_bound=" + maximumConfiguredConcurrency);
        startCase(sender, world, 0);
        return true;
    }

    private void startCase(CommandSender sender, World world, int caseIndex) {
        if (!running) {
            return;
        }
        if (caseIndex >= REQUESTER_COUNTS.length) {
            running = false;
            emit(sender, "FRONTIER_GENERATION_LOAD_OK cases=1,10,20,40 real_leaf_generation=true synthetic_requesters=true");
            return;
        }

        GenerationShieldMetrics baseline = shield.metrics();
        if (!baseline.healthy() || baseline.queued() != 0 || baseline.inFlight() != 0) {
            fail(sender, "generation shield was not healthy and idle before case " + caseIndex);
            return;
        }

        int requesters = REQUESTER_COUNTS[caseIndex];
        List<ChunkKey> keys;
        try {
            keys = caseKeys(world, caseIndex);
        } catch (RuntimeException exception) {
            fail(sender, "could not choose safe virgin coordinates: " + exception.getMessage());
            return;
        }

        for (int index = 0; index < keys.size(); index++) {
            String requester = "loadtest-" + caseIndex + "-requester-" + (index % requesters);
            GenerationAdmission admission = shield.request(requester, keys.get(index));
            if (admission != GenerationAdmission.QUEUED) {
                fail(sender, "case " + requesters + " requesters expected QUEUED but got " + admission
                        + " for " + keys.get(index));
                return;
            }
        }

        CaseRun run = new CaseRun(
                caseIndex,
                requesters,
                world,
                keys,
                baseline.started(),
                baseline.completed(),
                System.nanoTime());
        run.peakQueued = shield.metrics().queued();
        monitorTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> monitor(sender, run),
                1L,
                1L);
    }

    private void monitor(CommandSender sender, CaseRun run) {
        if (!running) {
            cancelMonitor();
            return;
        }
        long now = System.nanoTime();
        GenerationShieldMetrics metrics = shield.metrics();
        run.peakQueued = Math.max(run.peakQueued, metrics.queued());
        run.peakInFlight = Math.max(run.peakInFlight, metrics.inFlight());
        run.msptSamples.add(plugin.getServer().getAverageTickTime());
        run.peakHeapBytes = Math.max(run.peakHeapBytes, usedHeapBytes());

        if (!metrics.healthy()) {
            fail(sender, "generation shield became unhealthy during " + run.requesters + "-requester case");
            return;
        }
        if (run.peakInFlight > maximumConfiguredConcurrency) {
            fail(sender, "global concurrency exceeded configured upper bound: " + run.peakInFlight
                    + " > " + maximumConfiguredConcurrency);
            return;
        }

        long started = metrics.started() - run.startedBaseline;
        long completed = metrics.completed() - run.completedBaseline;
        if (started >= REQUESTS_PER_CASE && run.admissionsFinishedNanos == 0L) {
            run.admissionsFinishedNanos = now;
        }
        if (completed >= REQUESTS_PER_CASE && metrics.queued() == 0 && metrics.inFlight() == 0) {
            finishCase(sender, run, now);
            return;
        }
        if (now - run.startedNanos > CASE_TIMEOUT_NANOS) {
            fail(sender, "timed out after 120s in " + run.requesters + "-requester case; started="
                    + started + " completed=" + completed + " queued=" + metrics.queued()
                    + " inFlight=" + metrics.inFlight());
        }
    }

    private void finishCase(CommandSender sender, CaseRun run, long finishedNanos) {
        cancelMonitor();
        if (run.admissionsFinishedNanos == 0L) {
            fail(sender, "case completed without observing all generation admissions");
            return;
        }

        long minimumAdmissionNanos = (long) Math.floor(
                ((REQUESTS_PER_CASE - 1) / maximumConfiguredRate) * 1_000_000_000.0);
        long actualAdmissionNanos = run.admissionsFinishedNanos - run.startedNanos;
        if (actualAdmissionNanos + RATE_TOLERANCE_NANOS < minimumAdmissionNanos) {
            fail(sender, "aggregate generation rate exceeded configured upper bound in "
                    + run.requesters + "-requester case: admission_ms="
                    + TimeUnit.NANOSECONDS.toMillis(actualAdmissionNanos)
                    + " minimum_ms=" + TimeUnit.NANOSECONDS.toMillis(minimumAdmissionNanos));
            return;
        }

        double p50 = percentile(run.msptSamples, 0.50);
        double p95 = percentile(run.msptSamples, 0.95);
        double p99 = percentile(run.msptSamples, 0.99);
        double maxMspt = run.msptSamples.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double admissionSeconds = actualAdmissionNanos / 1_000_000_000.0;
        double totalSeconds = (finishedNanos - run.startedNanos) / 1_000_000_000.0;
        double peakHeapMiB = run.peakHeapBytes / (1024.0 * 1024.0);
        emit(sender, String.format(
                Locale.ROOT,
                "FRONTIER_GENERATION_LOAD_CASE requesters=%d requests=%d admission_seconds=%.3f total_seconds=%.3f "
                        + "configured_rate_upper_bound=%.3f peak_queue=%d peak_in_flight=%d "
                        + "mspt_p50=%.3f mspt_p95=%.3f mspt_p99=%.3f mspt_max=%.3f peak_heap_mib=%.1f",
                run.requesters,
                REQUESTS_PER_CASE,
                admissionSeconds,
                totalSeconds,
                maximumConfiguredRate,
                run.peakQueued,
                run.peakInFlight,
                p50,
                p95,
                p99,
                maxMspt,
                peakHeapMiB));

        for (ChunkKey key : run.keys) {
            if (run.world.isChunkLoaded(key.x(), key.z())) {
                run.world.unloadChunk(key.x(), key.z(), true);
            }
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> startCase(sender, run.world, run.caseIndex + 1),
                20L);
    }

    private List<ChunkKey> caseKeys(World world, int caseIndex) {
        CoreBoundaryPolicy policy = worldPolicies.get(world.getUID());
        if (policy == null) {
            throw new IllegalStateException("world has no runtime Frontier policy");
        }
        int coreEdgeChunk = Math.addExact(Math.floorDiv(policy.radiusBlocks(), 16), 1);
        int caseOffset = Math.multiplyExact(caseIndex, CASE_SEPARATION_CHUNKS);
        int base = Math.addExact(coreEdgeChunk, Math.addExact(CORE_CLEARANCE_CHUNKS, caseOffset));
        List<ChunkKey> keys = new ArrayList<>(REQUESTS_PER_CASE);
        for (int index = 0; index < REQUESTS_PER_CASE; index++) {
            int column = Math.floorMod(index, GRID_COLUMNS);
            int row = Math.floorDiv(index, GRID_COLUMNS);
            int x = Math.addExact(base, Math.multiplyExact(column, CHUNK_SPACING));
            int z = Math.addExact(base, Math.multiplyExact(row, CHUNK_SPACING));
            ChunkKey key = new ChunkKey(world.getUID().toString(), x, z);
            if (!policy.isManaged(key)) {
                throw new IllegalStateException("selected chunk is not managed: " + key);
            }
            if (!insideBorder(world, x, z)) {
                throw new IllegalStateException("selected chunk is outside the disposable world border: " + key);
            }
            if (world.isChunkGenerated(x, z)) {
                throw new IllegalStateException("selected load-test chunk is already generated: " + key);
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    private World firstManagedWorld() {
        for (UUID worldId : worldPolicies.keySet()) {
            World world = plugin.getServer().getWorld(worldId);
            if (world != null) {
                return world;
            }
        }
        return null;
    }

    private boolean isolatedSentinel(CommandSender sender) {
        String motd = PlainTextComponentSerializer.plainText().serialize(plugin.getServer().motd());
        return sender instanceof ConsoleCommandSender
                && plugin.getServer().getOnlinePlayers().isEmpty()
                && plugin.getServer().getMaxPlayers() <= 2
                && "127.0.0.1".equals(plugin.getServer().getIp())
                && SENTINEL_MOTD.equals(motd);
    }

    private static boolean insideBorder(World world, int chunkX, int chunkZ) {
        WorldBorder border = world.getWorldBorder();
        double halfSize = border.getSize() / 2.0;
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        double blockX = ((long) chunkX << 4) + 8.0;
        double blockZ = ((long) chunkZ << 4) + 8.0;
        return blockX >= centerX - halfSize + 16.0
                && blockX <= centerX + halfSize - 16.0
                && blockZ >= centerZ - halfSize + 16.0
                && blockZ <= centerZ + halfSize - 16.0;
    }

    private void fail(CommandSender sender, String reason) {
        cancelMonitor();
        running = false;
        String marker = "FRONTIER_GENERATION_LOAD_FAILED reason=" + reason;
        plugin.getLogger().severe(marker);
        sender.sendMessage("§c" + marker);
    }

    private void emit(CommandSender sender, String marker) {
        plugin.getLogger().info(marker);
        sender.sendMessage(marker);
    }

    private void cancelMonitor() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static double percentile(List<Double> samples, double percentile) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static final class CaseRun {
        private final int caseIndex;
        private final int requesters;
        private final World world;
        private final List<ChunkKey> keys;
        private final long startedBaseline;
        private final long completedBaseline;
        private final long startedNanos;
        private final List<Double> msptSamples = new ArrayList<>();
        private long admissionsFinishedNanos;
        private int peakQueued;
        private int peakInFlight;
        private long peakHeapBytes;

        private CaseRun(
                int caseIndex,
                int requesters,
                World world,
                List<ChunkKey> keys,
                long startedBaseline,
                long completedBaseline,
                long startedNanos) {
            this.caseIndex = caseIndex;
            this.requesters = requesters;
            this.world = world;
            this.keys = keys;
            this.startedBaseline = startedBaseline;
            this.completedBaseline = completedBaseline;
            this.startedNanos = startedNanos;
        }
    }
}
