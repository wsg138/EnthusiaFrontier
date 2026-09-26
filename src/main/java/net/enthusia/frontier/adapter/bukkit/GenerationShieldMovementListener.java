package net.enthusia.frontier.adapter.bukkit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.enthusia.frontier.application.GenerationBufferCoordinator;
import net.enthusia.frontier.application.GenerationBufferStatus;
import net.enthusia.frontier.config.GenerationShieldSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Fail-closed movement boundary for the generation shield.
 *
 * <p>Normal movement and player/entity teleports are stopped before unsafe advancement.
 * VehicleMoveEvent is not cancellable, so a configurable generated margin protects the
 * previous safe center while the vehicle is rolled back on the next server task.</p>
 */
public final class GenerationShieldMovementListener implements Listener {
    private static final long MESSAGE_COOLDOWN_NANOS = 1_000_000_000L;

    private final JavaPlugin plugin;
    private final GenerationBufferCoordinator buffers;
    private final GenerationShieldSettings settings;
    private final Set<UUID> managedWorlds;
    private final Set<UUID> vehicleRollbacks = new HashSet<>();
    private final Map<UUID, Long> lastMessageNanos = new HashMap<>();

    public GenerationShieldMovementListener(
            JavaPlugin plugin,
            GenerationBufferCoordinator buffers,
            GenerationShieldSettings settings,
            Set<UUID> managedWorlds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.buffers = Objects.requireNonNull(buffers, "buffers");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.managedWorlds = Set.copyOf(Objects.requireNonNull(managedWorlds, "managedWorlds"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        Location to = event.getTo();
        if (sameChunk(event.getFrom(), to)) {
            return;
        }
        if (!allow(event.getPlayer(), to)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent) {
            return;
        }
        if (!allow(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!allow(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Player rider = directPlayerPassenger(event.getEntity());
        if (rider != null && !allow(rider, event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        Player rider = directPlayerPassenger(vehicle);
        if (rider == null || sameChunk(event.getFrom(), event.getTo()) || allow(rider, event.getTo())) {
            return;
        }
        UUID vehicleId = vehicle.getUniqueId();
        if (!vehicleRollbacks.add(vehicleId)) {
            return;
        }
        Location rollback = event.getFrom().clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            vehicleRollbacks.remove(vehicleId);
            if (vehicle.isValid()) {
                vehicle.teleport(rollback);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String requester = requester(event.getPlayer());
        buffers.removeRequester(requester);
        lastMessageNanos.remove(event.getPlayer().getUniqueId());
    }

    private boolean allow(Player player, Location target) {
        if (target == null) {
            return false;
        }
        World world = target.getWorld();
        if (world == null || !managedWorlds.contains(world.getUID())) {
            return true;
        }
        int radius = Math.min(
                settings.maxGuardRadiusChunks(),
                Math.max(player.getViewDistance(), player.getSimulationDistance())
                        + settings.extraGuardRadiusChunks());
        GenerationBufferStatus status = buffers.prepare(
                requester(player),
                world.getUID().toString(),
                target.getBlockX() >> 4,
                target.getBlockZ() >> 4,
                radius);
        if (status == GenerationBufferStatus.READY) {
            return true;
        }
        notifyBlocked(player, status);
        return false;
    }

    private void notifyBlocked(Player player, GenerationBufferStatus status) {
        long now = System.nanoTime();
        long previous = lastMessageNanos.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
        if (now - previous < MESSAGE_COOLDOWN_NANOS && previous != Long.MIN_VALUE) {
            return;
        }
        lastMessageNanos.put(player.getUniqueId(), now);
        String message = status == GenerationBufferStatus.FAIL_CLOSED
                ? "Frontier exploration is paused for server safety."
                : "Frontier terrain is generating...";
        player.sendActionBar(Component.text(message));
    }

    private static Player directPlayerPassenger(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private static boolean sameChunk(Location first, Location second) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null) {
            return false;
        }
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && (first.getBlockX() >> 4) == (second.getBlockX() >> 4)
                && (first.getBlockZ() >> 4) == (second.getBlockZ() >> 4);
    }

    private static String requester(Player player) {
        return "player:" + player.getUniqueId();
    }
}
