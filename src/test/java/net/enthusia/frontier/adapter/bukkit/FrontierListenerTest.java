package net.enthusia.frontier.adapter.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.enthusia.frontier.application.FrontierMutation;
import net.enthusia.frontier.application.FrontierRepository;
import net.enthusia.frontier.application.FrontierStats;
import net.enthusia.frontier.application.FrontierTrackingService;
import net.enthusia.frontier.application.MutationJournal;
import net.enthusia.frontier.application.SafetyLatch;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

class FrontierListenerTest {
    @Test
    void forwardsGenerationAndPlayerActivityThroughThinAdapter() {
        RecordingRepository repository = new RecordingRepository();
        MutationJournal journal = new MutationJournal(repository, new NoopLatch(), 128, 32, ignored -> { });
        FrontierTrackingService tracking = new FrontierTrackingService(
                Map.of("world", new CoreBoundaryPolicy(0)),
                0,
                journal,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        FrontierListener listener = new FrontierListener(tracking);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        Block block = mock(Block.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000138");
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(uuid);
        when(chunk.getX()).thenReturn(4);
        when(chunk.getZ()).thenReturn(5);
        when(block.getWorld()).thenReturn(world);
        when(block.getChunk()).thenReturn(chunk);

        journal.start();

        ChunkLoadEvent existingLoad = mock(ChunkLoadEvent.class);
        when(existingLoad.isNewChunk()).thenReturn(false);
        listener.onChunkLoad(existingLoad);

        ChunkLoadEvent newLoad = mock(ChunkLoadEvent.class);
        when(newLoad.isNewChunk()).thenReturn(true);
        when(newLoad.getWorld()).thenReturn(world);
        when(newLoad.getChunk()).thenReturn(chunk);
        listener.onChunkLoad(newLoad);

        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getBlock()).thenReturn(block);
        listener.onBlockPlace(place);

        BlockBreakEvent breakEvent = mock(BlockBreakEvent.class);
        when(breakEvent.getBlock()).thenReturn(block);
        listener.onBlockBreak(breakEvent);

        PlayerBucketEmptyEvent empty = mock(PlayerBucketEmptyEvent.class);
        when(empty.getBlock()).thenReturn(block);
        listener.onBucketEmpty(empty);

        PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
        when(fill.getBlock()).thenReturn(block);
        listener.onBucketFill(fill);

        PlayerInteractEvent noBlock = mock(PlayerInteractEvent.class);
        when(noBlock.getClickedBlock()).thenReturn(null);
        listener.onInteract(noBlock);

        PlayerInteractEvent interact = mock(PlayerInteractEvent.class);
        when(interact.getClickedBlock()).thenReturn(block);
        listener.onInteract(interact);

        journal.close();

        assertEquals(6, repository.applied.size());
        assertEquals(1, repository.applied.stream().filter(FrontierMutation.Generated.class::isInstance).count());
        assertEquals(5, repository.applied.stream().filter(FrontierMutation.Protected.class::isInstance).count());
    }

    private static final class RecordingRepository implements FrontierRepository {
        private final List<FrontierMutation> applied = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void initialize() {
        }

        @Override
        public void applyBatch(List<FrontierMutation> mutations) {
            applied.addAll(mutations);
        }

        @Override
        public FrontierStats stats() {
            return new FrontierStats(0, 0, 0);
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopLatch implements SafetyLatch {
        @Override
        public void trip(String reason) {
        }

        @Override
        public boolean isTripped() {
            return false;
        }

        @Override
        public String reason() {
            return "none";
        }
    }
}
