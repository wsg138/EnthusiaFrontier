package net.enthusia.frontier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import net.enthusia.frontier.config.CleanupSettings;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;

/** Destructive cleanup policy. All Bukkit/Paper/Moonrise behavior is delegated through ports. */
public final class CleanupService {
    private final CleanupSettings settings;
    private final FrontierTrackingService tracking;
    private final MutationJournal journal;
    private final SafetyLatch safetyLatch;
    private final CleanupEnvironmentPort environment;
    private final StorageReclaimPort storage;
    private final Clock clock;

    public CleanupService(
            CleanupSettings settings,
            FrontierTrackingService tracking,
            MutationJournal journal,
            SafetyLatch safetyLatch,
            CleanupEnvironmentPort environment,
            StorageReclaimPort storage,
            Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tracking = Objects.requireNonNull(tracking, "tracking");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.safetyLatch = Objects.requireNonNull(safetyLatch, "safetyLatch");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CleanupResult process(String worldName, CleanupCandidate candidate) throws Exception {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(candidate, "candidate");
        ChunkKey key = candidate.key();
        if (safetyLatch.isTripped()) {
            return CleanupResult.LATCHED;
        }
        if (!journal.isIdle() || environment.recentMspt() > settings.maxMspt()) {
            return CleanupResult.DEFERRED;
        }
        if (tracking.isProtectedInMemory(key)) {
            return CleanupResult.PROTECTED;
        }
        if (!environment.isSafeToClear(worldName, key, settings.minPlayerDistanceChunks())) {
            return CleanupResult.DEFERRED;
        }
        if (settings.dryRun()) {
            return CleanupResult.DRY_RUN;
        }

        storage.clearChunk(worldName, key);
        if (!journal.submit(new FrontierMutation.Deleted(key, Instant.now(clock)))) {
            return CleanupResult.LATCHED;
        }
        return CleanupResult.CLEARED;
    }

    public RegionReclaimResult reclaim(String worldName, RegionKey region) throws Exception {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(region, "region");
        if (safetyLatch.isTripped() || !journal.isIdle()) {
            return RegionReclaimResult.DEFERRED_OPEN;
        }
        if (!settings.physicalReclaim() || settings.dryRun()) {
            return RegionReclaimResult.UNSUPPORTED;
        }
        return storage.reclaimEmptyRegion(worldName, region);
    }
}
