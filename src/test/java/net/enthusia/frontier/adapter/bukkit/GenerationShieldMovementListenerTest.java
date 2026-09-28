package net.enthusia.frontier.adapter.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.enthusia.frontier.application.GenerationBufferCoordinator;
import net.enthusia.frontier.application.GenerationBufferStatus;
import net.enthusia.frontier.config.GenerationShieldSettings;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class GenerationShieldMovementListenerTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000138");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000139");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000140");

    @Test
    void requiredGuardRadiusUsesLargestRuntimeDistancePlusMargin() {
        Player player = player(4, 7, 5);

        assertEquals(9, GenerationShieldMovementListener.requiredGuardRadius(player, 2));
    }

    @Test
    void passengerSearchFindsNestedPlayerAndToleratesCycles() {
        Entity root = mock(Entity.class);
        Entity middle = mock(Entity.class);
        Player player = mock(Player.class);
        when(root.getPassengers()).thenReturn(List.of(middle));
        when(middle.getPassengers()).thenReturn(List.of(player));

        assertSame(player, GenerationShieldMovementListener.playerPassenger(root));

        Entity cycleRoot = mock(Entity.class);
        Entity cycleChild = mock(Entity.class);
        when(cycleRoot.getPassengers()).thenReturn(List.of(cycleChild));
        when(cycleChild.getPassengers()).thenReturn(List.of(cycleRoot));

        assertNull(GenerationShieldMovementListener.playerPassenger(cycleRoot));
    }

    @Test
    void sameChunkMoveWarmsCurrentBufferWithoutCancellingMovement() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldMovementListener listener = listener(buffers, settings(2, 12));
        World world = world();
        Player player = player(4, 7, 5);
        Location from = location(world, 1, 1);
        Location to = location(world, 15, 15);
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(buffers.prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 0, 0, 9))
                .thenReturn(GenerationBufferStatus.PENDING);

        listener.onMove(event);

        verify(buffers).prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 0, 0, 9);
        verify(buffers, never()).prewarm(any(), any(), any(Integer.class), any(Integer.class), any(Integer.class));
        verify(event, never()).setCancelled(true);
    }

    @Test
    void readySameChunkMovePrewarmsLikelyNextChunk() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldMovementListener listener = listener(buffers, settings(2, 12));
        World world = world();
        Player player = player(4, 7, 5);
        Location from = location(world, 1, 1);
        Location to = location(world, 2, 1);
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(buffers.prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 0, 0, 9))
                .thenReturn(GenerationBufferStatus.READY);

        listener.onMove(event);

        verify(buffers).prewarm("player:" + PLAYER_ID, WORLD_ID.toString(), 1, 0, 9);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void highSpeedCrossChunkMoveIsCancelledUntilDestinationBufferIsReady() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldMovementListener listener = listener(buffers, settings(2, 12));
        World world = world();
        Player player = player(4, 7, 5);
        Location from = location(world, 0, 0);
        Location to = location(world, 160, 0);
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(buffers.prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 10, 0, 9))
                .thenReturn(GenerationBufferStatus.PENDING);

        listener.onMove(event);

        verify(event).setCancelled(true);
        verify(buffers).prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 10, 0, 9);
    }

    @Test
    void guardRadiusOverflowFailsClosedWithoutSubmittingPartialBuffer() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldMovementListener listener = listener(buffers, settings(2, 8));
        World world = world();
        Player player = player(9, 9, 9);
        Location from = location(world, 0, 0);
        Location to = location(world, 32, 0);
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);

        listener.onMove(event);

        verify(event).setCancelled(true);
        verifyNoInteractions(buffers);
    }

    @Test
    void nestedRiderEntityTeleportIsCancelledWhenFrontierIsNotReady() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldMovementListener listener = listener(buffers, settings(2, 12));
        World world = world();
        Player player = player(4, 4, 4);
        Entity root = mock(Entity.class);
        Entity middle = mock(Entity.class);
        when(root.getPassengers()).thenReturn(List.of(middle));
        when(middle.getPassengers()).thenReturn(List.of(player));
        Location to = location(world, 80, 96);
        EntityTeleportEvent event = mock(EntityTeleportEvent.class);
        when(event.getEntity()).thenReturn(root);
        when(event.getTo()).thenReturn(to);
        when(buffers.prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 5, 6, 6))
                .thenReturn(GenerationBufferStatus.PENDING);

        listener.onEntityTeleport(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unsafePortalIsCancelledBeforeTravel() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldMovementListener listener = listener(buffers, settings(2, 12));
        World world = world();
        Player player = player(4, 4, 4);
        Location to = location(world, 112, 128);
        PlayerPortalEvent event = mock(PlayerPortalEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getTo()).thenReturn(to);
        when(buffers.prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 7, 8, 6))
                .thenReturn(GenerationBufferStatus.PENDING);

        listener.onPortal(event);

        verify(event).setCancelled(true);
    }

    @Test
    void nestedRiderVehicleMoveSchedulesRollbackWhenDestinationIsUnsafe() {
        GenerationBufferCoordinator buffers = mock(GenerationBufferCoordinator.class);
        GenerationShieldSettings settings = settings(2, 12);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        GenerationShieldMovementListener listener =
                new GenerationShieldMovementListener(plugin, buffers, settings, Set.of(WORLD_ID));

        World world = world();
        Player player = player(4, 4, 4);
        Vehicle vehicle = mock(Vehicle.class);
        Entity middle = mock(Entity.class);
        when(vehicle.getUniqueId()).thenReturn(VEHICLE_ID);
        when(vehicle.getPassengers()).thenReturn(List.of(middle));
        when(middle.getPassengers()).thenReturn(List.of(player));

        Location from = location(world, 0, 0);
        Location rollback = mock(Location.class);
        when(from.clone()).thenReturn(rollback);
        Location to = location(world, 144, 160);
        VehicleMoveEvent event = mock(VehicleMoveEvent.class);
        when(event.getVehicle()).thenReturn(vehicle);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(buffers.prepare("player:" + PLAYER_ID, WORLD_ID.toString(), 9, 10, 6))
                .thenReturn(GenerationBufferStatus.PENDING);

        listener.onVehicleMove(event);

        verify(scheduler).runTask(eq(plugin), any(Runnable.class));
    }

    private static GenerationShieldMovementListener listener(
            GenerationBufferCoordinator buffers,
            GenerationShieldSettings settings) {
        return new GenerationShieldMovementListener(mock(JavaPlugin.class), buffers, settings, Set.of(WORLD_ID));
    }

    private static GenerationShieldSettings settings(int extraRadius, int maxRadius) {
        GenerationShieldSettings settings = mock(GenerationShieldSettings.class);
        when(settings.extraGuardRadiusChunks()).thenReturn(extraRadius);
        when(settings.maxGuardRadiusChunks()).thenReturn(maxRadius);
        return settings;
    }

    private static Player player(int viewDistance, int sendViewDistance, int simulationDistance) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getViewDistance()).thenReturn(viewDistance);
        when(player.getSendViewDistance()).thenReturn(sendViewDistance);
        when(player.getSimulationDistance()).thenReturn(simulationDistance);
        return player;
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        return world;
    }

    private static Location location(World world, int blockX, int blockZ) {
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockX()).thenReturn(blockX);
        when(location.getBlockZ()).thenReturn(blockZ);
        when(location.getX()).thenReturn((double) blockX);
        when(location.getZ()).thenReturn((double) blockZ);
        return location;
    }
}
