package net.enthusia.frontier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
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
    private final Consumer<String> auditSink;
    private final Consumer<ChunkKey> deletedChunkSink;

    public CleanupService(
            CleanupSettings settings,
            FrontierTrackingService tracking,
            MutationJournal journal,
            SafetyLatch safetyLatch,
            CleanupEnvironmentPort environment,
            StorageReclaimPort storage,
            Clock clock,
            Consumer<String> auditSink) {
        this(settings, tracking, journal, safetyLatch, environment, storage, clock, auditSink, ignored -> { });
    }

    public CleanupService(
            CleanupSettings settings,
            FrontierTrackingService tracking,
            MutationJournal journal,
            SafetyLatch safetyLatch,
            CleanupEnvironmentPort environment,
            StorageReclaimPort storage,
            Clock clock,
            Consumer<String> auditSink,
            Consumer<ChunkKey> deletedChunkSink) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tracking = Objects.requireNonNull(tracking, "tracking");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.safetyLatch = Objects.requireNonNull(safetyLatch, "safetyLatch");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.deletedChunkSink = Objects.requireNonNull(deletedChunkSink, "deletedChunkSink");
    }

    public CleanupResult process(String worldName, CleanupCandidate candidate) throws Exception {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(candidate, "candidate");
        ChunkKey key = candidate.key();
        if (safetyLatch.isTripped()) {
            auditDecision(worldName, candidate, "latched", "safety_latch");
            return CleanupResult.LATCHED;
        }
        if (!journal.isIdle()) {
            auditDecision(worldName, candidate, "deferred", "journal_busy");
            return CleanupResult.DEFERRED;
        }
        double mspt = environment.recentMspt();
        if (mspt > settings.maxMspt()) {
            auditDecision(worldName, candidate, "deferred", "mspt_" + mspt);
            return CleanupResult.DEFERRED;
        }
        if (tracking.isProtectedInMemory(key)) {
            auditDecision(worldName, candidate, "protected", "player_activity");
            return CleanupResult.PROTECTED;
        }
        if (!environment.isSafeToClear(worldName, key, settings.minPlayerDistanceChunks())) {
            auditDecision(worldName, candidate, "deferred", "environment_not_safe");
            return CleanupResult.DEFERRED;
        }
        if (settings.dryRun()) {
            auditDecision(worldName, candidate, "dry_run", "eligible");
            return CleanupResult.DRY_RUN;
        }
        if (!candidate.hasReclaimIntent()) {
            safetyLatch.trip("destructive cleanup candidate lacked durable reclaim intent");
            auditDecision(worldName, candidate, "latched", "missing_reclaim_intent");
            return CleanupResult.LATCHED;
        }

        storage.clearChunk(worldName, key);
        // Storage no longer contains the chunk. Invalidate the hot readiness view
        // immediately so same-process re-entry can never treat reclaimed terrain as ready.
        deletedChunkSink.accept(key);
        if (!journal.submit(new FrontierMutation.Deleted(key, Instant.now(clock)))) {
            auditDecision(worldName, candidate, "latched", "deleted_marker_submit_failed");
            return CleanupResult.LATCHED;
        }
        auditDecision(worldName, candidate, "cleared", "logical_storage_clear_complete");
        return CleanupResult.CLEARED;
    }

    public RegionReclaimResult reclaim(String worldName, RegionKey region) throws Exception {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(region, "region");
        if (safetyLatch.isTripped()) {
            auditRegion(worldName, region, RegionReclaimResult.DEFERRED_OPEN, "safety_latch");
            return RegionReclaimResult.DEFERRED_OPEN;
        }
        if (!journal.isIdle()) {
            auditRegion(worldName, region, RegionReclaimResult.DEFERRED_OPEN, "journal_busy");
            return RegionReclaimResult.DEFERRED_OPEN;
        }
        if (!settings.physicalReclaim() || settings.dryRun()) {
            auditRegion(worldName, region, RegionReclaimResult.UNSUPPORTED, "physical_reclaim_disabled");
            return RegionReclaimResult.UNSUPPORTED;
        }
        RegionReclaimResult result = storage.reclaimEmptyRegion(worldName, region);
        auditRegion(worldName, region, result, "storage_adapter");
        return result;
    }

    private void auditDecision(String worldName, CleanupCandidate candidate, String outcome, String reason) {
        ChunkKey key = candidate.key();
        auditSink.accept("FRONTIER_CLEANUP_AUDIT stage=chunk outcome=" + outcome
                + " reason=" + reason
                + " world=" + worldName
                + " chunk=" + key.x() + "," + key.z()
                + " generatedAt=" + candidate.generatedAt()
                + " reclaimIntent=" + (candidate.hasReclaimIntent() ? candidate.reclaimIntentAt() : "none"));
    }

    private void auditRegion(String worldName, RegionKey region, RegionReclaimResult outcome, String reason) {
        auditSink.accept("FRONTIER_CLEANUP_AUDIT stage=region outcome=" + outcome.name().toLowerCase(Locale.ROOT)
                + " reason=" + reason
                + " world=" + worldName
                + " region=" + region.x() + "," + region.z());
    }
}
