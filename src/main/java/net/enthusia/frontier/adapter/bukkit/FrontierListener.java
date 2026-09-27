package net.enthusia.frontier.adapter.bukkit;

import java.util.Objects;
import net.enthusia.frontier.application.FrontierTrackingService;
import net.enthusia.frontier.application.GenerationBufferCoordinator;
import net.enthusia.frontier.domain.ActivityKind;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/** Thin Bukkit event adapter. Event handlers only enqueue immutable application mutations. */
public final class FrontierListener implements Listener {
    private final FrontierTrackingService tracking;
    private final GenerationBufferCoordinator generationBuffers;

    public FrontierListener(FrontierTrackingService tracking) {
        this(tracking, null);
    }

    public FrontierListener(
            FrontierTrackingService tracking,
            GenerationBufferCoordinator generationBuffers) {
        this.tracking = Objects.requireNonNull(tracking, "tracking");
        this.generationBuffers = generationBuffers;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        if (generationBuffers == null) {
            tracking.recordGenerated(
                    event.getWorld().getName(),
                    event.getWorld().getUID(),
                    event.getChunk().getX(),
                    event.getChunk().getZ());
            return;
        }

        tracking.recordGeneratedDurably(
                        event.getWorld().getName(),
                        event.getWorld().getUID(),
                        event.getChunk().getX(),
                        event.getChunk().getZ())
                .ifPresent(observation -> generationBuffers.observeGenerated(
                        observation.key(), observation.committed()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        protect(event.getBlock(), ActivityKind.BLOCK_PLACE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        protect(event.getBlock(), ActivityKind.BLOCK_BREAK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protect(event.getBlock(), ActivityKind.BUCKET);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protect(event.getBlock(), ActivityKind.BUCKET);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            protect(clicked, ActivityKind.BLOCK_INTERACT);
        }
    }

    private void protect(Block block, ActivityKind kind) {
        tracking.recordActivity(
                block.getWorld().getName(),
                block.getWorld().getUID(),
                block.getChunk().getX(),
                block.getChunk().getZ(),
                kind);
    }
}
