package net.enthusia.frontier.adapter.bukkit;

import java.time.Duration;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.enthusia.frontier.application.CleanupCandidate;
import net.enthusia.frontier.application.CleanupResult;
import net.enthusia.frontier.application.CleanupService;
import net.enthusia.frontier.application.FrontierRepository;
import net.enthusia.frontier.application.RegionReclaimResult;
import net.enthusia.frontier.application.SafetyLatch;
import net.enthusia.frontier.config.CleanupSettings;
import net.enthusia.frontier.adapter.paper.McaRegionFileInspector;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Bounded scheduler adapter: async SQLite scans, sync world/storage mutations. */
public final class BukkitCleanupCoordinator implements AutoCloseable {
    private final JavaPlugin plugin;
    private final CleanupSettings settings;
    private final Set<String> managedWorldNames;
    private final FrontierRepository repository;
    private final CleanupService service;
    private final SafetyLatch safetyLatch;
    private final ConcurrentLinkedQueue<CandidateWork> candidates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RegionWork> regions = new ConcurrentLinkedQueue<>();
    private final Set<ChunkKey> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<RegionKey> queuedRegions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanRunning = new AtomicBoolean();
    private final AtomicLong dryRunEligible = new AtomicLong();
    private final AtomicLong logicallyCleared = new AtomicLong();
    private final AtomicLong physicallyReclaimed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private BukkitTask scanTask;
    private BukkitTask workerTask;

    public BukkitCleanupCoordinator(
            JavaPlugin plugin,
            CleanupSettings settings,
            Set<String> managedWorldNames,
            FrontierRepository repository,
            CleanupService service,
            SafetyLatch safetyLatch) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.managedWorldNames = Set.copyOf(Objects.requireNonNull(managedWorldNames, "managedWorldNames"));
        this.repository = Objects.requireNonNull(repository, "repository");
        this.service = Objects.requireNonNull(service, "service");
        this.safetyLatch = Objects.requireNonNull(safetyLatch, "safetyLatch");
    }

    public void start() {
        if (!settings.enabled()) {
            return;
        }
        if (scanTask != null || workerTask != null) {
            throw new IllegalStateException("cleanup coordinator already started");
        }
        scanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::scheduleScan, 20L, settings.scanPeriodTicks());
        workerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::processTick, 1L, 1L);
    }

    public String status() {
        String mode = !settings.enabled() ? "disabled" : settings.dryRun() ? "dry-run" : "active";
        return mode
                + " queued=" + candidates.size()
                + " dryEligible=" + dryRunEligible.get()
                + " cleared=" + logicallyCleared.get()
                + " regionsReclaimed=" + physicallyReclaimed.get()
                + " deferred=" + deferred.get();
    }

    @Override
    public void close() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }
        candidates.clear();
        regions.clear();
        queuedChunks.clear();
        queuedRegions.clear();
    }

    private void scheduleScan() {
        if (safetyLatch.isTripped() || !scanRunning.compareAndSet(false, true)) {
            return;
        }
        Map<String, WorldStorage> worlds = snapshotWorlds();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> scan(worlds));
    }

    private record WorldStorage(String uuid, Path folder) {
        static WorldStorage of(World world) {
            return new WorldStorage(world.getUID().toString(), world.getWorldFolder().toPath());
        }
    }

    private Map<String, WorldStorage> snapshotWorlds() {
        return managedWorldNames.stream()
                .map(name -> plugin.getServer().getWorld(name))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(World::getName, WorldStorage::of));
    }

    private void scan(Map<String, WorldStorage> worlds) {
        List<CandidateWork> discoveredCandidates = new ArrayList<>();
        List<RegionWork> discoveredRegions = new ArrayList<>();
        try {
            Instant observedAt = Instant.now();
            Instant cutoff = observedAt.minus(Duration.ofDays(settings.untouchedRetentionDays()));
            for (var world : worlds.entrySet()) {
                scanWorld(world.getKey(), world.getValue(), observedAt, cutoff, discoveredCandidates, discoveredRegions);
            }
        } catch (Exception exception) {
            safetyLatch.trip("frontier cleanup candidate scan failed: " + exception.getClass().getSimpleName());
            plugin.getLogger().log(Level.SEVERE, "Frontier cleanup scan failed closed", exception);
        } finally {
            for (CandidateWork work : discoveredCandidates) {
                if (queuedChunks.add(work.candidate().key())) {
                    candidates.offer(work);
                }
            }
            for (RegionWork work : discoveredRegions) {
                if (queuedRegions.add(work.region())) {
                    regions.offer(work);
                }
            }
            scanRunning.set(false);
        }
    }

    private void scanWorld(String name, WorldStorage world, Instant observedAt, Instant cutoff,
                           List<CandidateWork> discoveredCandidates, List<RegionWork> discoveredRegions) throws Exception {
        List<CleanupCandidate> found = settings.dryRun()
                ? repository.findCleanupCandidates(world.uuid(), cutoff, settings.candidateBatchSize())
                : repository.reserveCleanupCandidates(
                        world.uuid(), cutoff, settings.candidateBatchSize(), observedAt);
        for (CleanupCandidate candidate : found) {
            discoveredCandidates.add(new CandidateWork(name, candidate));
        }
        if (settings.physicalReclaim() && !settings.dryRun()) {
            var batch = repository.findDeletedRegions(world.uuid(), settings.candidateBatchSize());
            for (RegionKey region : McaRegionFileInspector.reclaimCandidates(world.folder(), batch)) {
                discoveredRegions.add(new RegionWork(name, region));
            }
        }
    }

    private void processTick() {
        if (safetyLatch.isTripped()) {
            return;
        }
        int budget = settings.maxChunksPerTick();
        while (budget-- > 0) {
            CandidateWork work = candidates.poll();
            if (work == null) {
                break;
            }
            queuedChunks.remove(work.candidate().key());
            try {
                record(service.process(work.worldName(), work.candidate()));
            } catch (Exception exception) {
                safetyLatch.trip("frontier destructive cleanup failed: " + exception.getClass().getSimpleName());
                plugin.getLogger().log(Level.SEVERE, "Frontier cleanup failed closed", exception);
                return;
            }
        }

        RegionWork region = regions.poll();
        if (region != null) {
            queuedRegions.remove(region.region());
            try {
                RegionReclaimResult result = service.reclaim(region.worldName(), region.region());
                if (result == RegionReclaimResult.RECLAIMED) {
                    physicallyReclaimed.incrementAndGet();
                } else if (result == RegionReclaimResult.DEFERRED_OPEN
                        || result == RegionReclaimResult.NOT_EMPTY) {
                    deferred.incrementAndGet();
                } else if (result == RegionReclaimResult.UNSUPPORTED && settings.requireSupportedAdapter()) {
                    safetyLatch.trip("physical region reclamation became unsupported");
                }
            } catch (Exception exception) {
                safetyLatch.trip("frontier physical reclamation failed: " + exception.getClass().getSimpleName());
                plugin.getLogger().log(Level.SEVERE, "Frontier physical reclamation failed closed", exception);
            }
        }
    }

    private void record(CleanupResult result) {
        switch (result) {
            case DRY_RUN -> dryRunEligible.incrementAndGet();
            case CLEARED -> logicallyCleared.incrementAndGet();
            case DEFERRED -> deferred.incrementAndGet();
            case PROTECTED, LATCHED -> { }
        }
    }

    private record CandidateWork(String worldName, CleanupCandidate candidate) {
        private CandidateWork {
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    private record RegionWork(String worldName, RegionKey region) {
        private RegionWork {
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(region, "region");
        }
    }
}
