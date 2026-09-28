package net.enthusia.frontier.adapter.bukkit;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adaptive movement boundary for Frontier generation.
 *
 * <p>While the global generation shield is healthy, player movement and teleports are
 * soft-gated: Frontier does not artificially stop travel or require an entire
 * view-distance square before movement may complete. Paper's own chunk loader and
 * Frontier's adaptive Paper generation throttle handle normal live exploration. If the
 * global shield pauses or becomes unhealthy, Frontier falls back to the full fail-closed
 * readiness buffer before movement may advance.</p>
 *
 * <p>VehicleMoveEvent is not cancellable, so unsafe vehicle advancement in hard-gate
 * mode is rolled back on the next server task.</p>
 */
public final class GenerationShieldMovementListener implements Listener {
    private static final long MESSAGE_COOLDOWN_NANOS = 1_000_000_000L;
    private static final double MOVEMENT_EPSILON = 1.0e-6;

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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!buffers.canSoftAdvance()) {
            prepare(event.getPlayer(), event.getPlayer().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        Location to = event.getTo();

        if (buffers.canSoftAdvance()) {
            return;
        }

        if (sameChunk(event.getFrom(), to)) {
            GenerationBufferStatus current = prepare(event.getPlayer(), to);
            if (current == GenerationBufferStatus.READY) {
                prewarmAhead(event.getPlayer(), event.getFrom(), to);
            }
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
        if (buffers.canSoftAdvance()) {
            return;
        }
        if (!allow(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (buffers.canSoftAdvance()) {
            return;
        }
        if (!allow(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Player rider = playerPassenger(event.getEntity());
        if (rider == null || buffers.canSoftAdvance()) {
            return;
        }
        if (!allow(rider, event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        Player rider = playerPassenger(vehicle);
        if (rider == null) {
            return;
        }

        if (buffers.canSoftAdvance()) {
            return;
        }

        if (sameChunk(event.getFrom(), event.getTo())) {
            GenerationBufferStatus current = prepare(rider, event.getTo());
            if (current == GenerationBufferStatus.READY) {
                prewarmAhead(rider, event.getFrom(), event.getTo());
            }
            return;
        }
        if (allow(rider, event.getTo())) {
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
        GenerationBufferStatus status = prepare(player, target);
        if (status == GenerationBufferStatus.READY) {
            return true;
        }
        notifyBlocked(player, status);
        return false;
    }

    private GenerationBufferStatus prepare(Player player, Location target) {
        if (target == null) {
            return GenerationBufferStatus.FAIL_CLOSED;
        }
        World world = target.getWorld();
        if (world == null || !managedWorlds.contains(world.getUID())) {
            return GenerationBufferStatus.READY;
        }

        int radius = requiredGuardRadius(player, settings.extraGuardRadiusChunks());
        if (radius > settings.maxGuardRadiusChunks()) {
            return GenerationBufferStatus.FAIL_CLOSED;
        }

        return buffers.prepare(
                requester(player),
                world.getUID().toString(),
                target.getBlockX() >> 4,
                target.getBlockZ() >> 4,
                radius);
    }

    private void prewarmAhead(Player player, Location from, Location to) {
        if (from == null || to == null) {
            return;
        }
        World world = to.getWorld();
        if (world == null || !managedWorlds.contains(world.getUID())) {
            return;
        }

        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        double absoluteX = Math.abs(deltaX);
        double absoluteZ = Math.abs(deltaZ);
        if (absoluteX < MOVEMENT_EPSILON && absoluteZ < MOVEMENT_EPSILON) {
            return;
        }

        int stepX = 0;
        int stepZ = 0;
        if (absoluteX >= absoluteZ) {
            stepX = deltaX > 0.0 ? 1 : -1;
        } else {
            stepZ = deltaZ > 0.0 ? 1 : -1;
        }

        int radius = requiredGuardRadius(player, settings.extraGuardRadiusChunks());
        if (radius > settings.maxGuardRadiusChunks()) {
            return;
        }

        int currentX = to.getBlockX() >> 4;
        int currentZ = to.getBlockZ() >> 4;
        buffers.prewarm(
                requester(player),
                world.getUID().toString(),
                currentX,
                currentZ,
                currentX + stepX,
                currentZ + stepZ,
                radius);
    }

    static int requiredGuardRadius(Player player, int extraGuardRadiusChunks) {
        Objects.requireNonNull(player, "player");
        if (extraGuardRadiusChunks < 0) {
            throw new IllegalArgumentException("extraGuardRadiusChunks must be >= 0");
        }
        int runtimeDistance = Math.max(
                Math.max(player.getViewDistance(), player.getSendViewDistance()),
                player.getSimulationDistance());
        return Math.addExact(runtimeDistance, extraGuardRadiusChunks);
    }

    /** Returns a player anywhere in the passenger tree while tolerating malformed cycles. */
    static Player playerPassenger(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Entity> pending = new ArrayDeque<>();
        visited.add(entity);
        pending.addAll(entity.getPassengers());
        while (!pending.isEmpty()) {
            Entity passenger = pending.removeFirst();
            if (!visited.add(passenger)) {
                continue;
            }
            if (passenger instanceof Player player) {
                return player;
            }
            pending.addAll(passenger.getPassengers());
        }
        return null;
    }

    private void notifyBlocked(Player player, GenerationBufferStatus status) {
        long now = System.nanoTime();
        long previous = lastMessageNanos.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
        if (now - previous < MESSAGE_COOLDOWN_NANOS && previous != Long.MIN_VALUE) {
            return;
        }
        lastMessageNanos.put(player.getUniqueId(), now);
        String message;
        if (status == GenerationBufferStatus.FAIL_CLOSED) {
            message = "Frontier exploration is paused for server safety.";
        } else if (buffers.generationPaused()) {
            message = "Frontier generation is paused until server load recovers.";
        } else {
            int remaining = buffers.pendingChunks(requester(player));
            message = remaining > 0
                    ? "Frontier terrain is catching up (" + remaining + " chunks)..."
                    : "Frontier terrain is catching up...";
        }
        player.sendActionBar(Component.text(message));
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
