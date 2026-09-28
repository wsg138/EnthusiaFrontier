package net.enthusia.frontier.adapter.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.enthusia.frontier.domain.ChunkKey;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class BukkitCleanupEnvironmentAdapterTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000138");

    @Test
    void runtimeMoonriseFootprintRaisesConfiguredPlayerMinimum() {
        Player player = playerAtChunk(0, 0, 10, 8, 6);

        assertEquals(12, BukkitCleanupEnvironmentAdapter.requiredPlayerExclusionRadius(player, 8));
        assertEquals(20, BukkitCleanupEnvironmentAdapter.requiredPlayerExclusionRadius(player, 20));
    }

    @Test
    void pendingPlayerFootprintDefersEvenWhenChunkIsNotLoaded() {
        Fixture fixture = new Fixture();
        Player player = playerAtChunk(0, 0, 10, 8, 6);
        when(fixture.world.getPlayers()).thenReturn(List.of(player));

        assertFalse(fixture.adapter.isSafeToClear("world", key(12, 0), 8));
        assertTrue(fixture.adapter.isSafeToClear("world", key(13, 0), 8));
    }

    @Test
    void configuredPlayerMinimumStillAppliesWhenRuntimeDistanceIsSmaller() {
        Fixture fixture = new Fixture();
        Player player = playerAtChunk(0, 0, 2, 2, 2);
        when(fixture.world.getPlayers()).thenReturn(List.of(player));

        assertFalse(fixture.adapter.isSafeToClear("world", key(8, 8), 8));
        assertTrue(fixture.adapter.isSafeToClear("world", key(9, 9), 8));
    }

    @Test
    void pluginTicketDefersBeforeFullChunkLoadIsVisible() {
        Fixture fixture = new Fixture();
        Plugin owner = mock(Plugin.class);
        when(fixture.world.getPluginChunkTickets(30, 31)).thenReturn(List.of(owner));

        assertFalse(fixture.adapter.isSafeToClear("world", key(30, 31), 8));
    }

    @Test
    void loadedForceLoadedAndWorldIdentityChecksFailClosed() {
        Fixture loaded = new Fixture();
        when(loaded.world.isChunkLoaded(20, 21)).thenReturn(true);
        assertFalse(loaded.adapter.isSafeToClear("world", key(20, 21), 8));

        Fixture forced = new Fixture();
        when(forced.world.isChunkForceLoaded(22, 23)).thenReturn(true);
        assertFalse(forced.adapter.isSafeToClear("world", key(22, 23), 8));

        Fixture wrongWorld = new Fixture();
        ChunkKey foreign = new ChunkKey("00000000-0000-0000-0000-000000000999", 24, 25);
        assertFalse(wrongWorld.adapter.isSafeToClear("world", foreign, 8));
        assertFalse(wrongWorld.adapter.isSafeToClear("missing", key(24, 25), 8));
    }

    private static ChunkKey key(int x, int z) {
        return new ChunkKey(WORLD_ID.toString(), x, z);
    }

    private static Player playerAtChunk(
            int chunkX,
            int chunkZ,
            int viewDistance,
            int sendViewDistance,
            int simulationDistance) {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(location.getBlockX()).thenReturn(chunkX << 4);
        when(location.getBlockZ()).thenReturn(chunkZ << 4);
        when(player.getLocation()).thenReturn(location);
        when(player.getViewDistance()).thenReturn(viewDistance);
        when(player.getSendViewDistance()).thenReturn(sendViewDistance);
        when(player.getSimulationDistance()).thenReturn(simulationDistance);
        return player;
    }

    private static final class Fixture {
        private final Server server = mock(Server.class);
        private final World world = mock(World.class);
        private final BukkitCleanupEnvironmentAdapter adapter = new BukkitCleanupEnvironmentAdapter(server);

        private Fixture() {
            when(server.getWorld("world")).thenReturn(world);
            when(world.getUID()).thenReturn(WORLD_ID);
            when(world.getPlayers()).thenReturn(List.of());
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            when(world.isChunkForceLoaded(anyInt(), anyInt())).thenReturn(false);
            when(world.getPluginChunkTickets(anyInt(), anyInt())).thenReturn(List.of());
        }
    }
}
