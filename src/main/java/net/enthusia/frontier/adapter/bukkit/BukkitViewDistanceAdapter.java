package net.enthusia.frontier.adapter.bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.frontier.application.ViewDistancePort;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies only the per-player loading view distance without changing simulation
 * or send-view distance.
 *
 * <p>Paper exposes loading and sending as separate controls. Changing the
 * send-view distance also changes the radius announced to the client, which can
 * make the client rebuild its render-distance state and visibly flash. Frontier
 * only needs the loading view-distance ceiling for its adaptive pressure control,
 * so the player's inherited send distance is deliberately left untouched.</p>
 */
public final class BukkitViewDistanceAdapter implements ViewDistancePort, Listener {
    private final JavaPlugin plugin;
    private final Server server;
    private final Map<UUID, Integer> originalViewDistances = new HashMap<>();
    private int targetViewDistance = -1;

    public BukkitViewDistanceAdapter(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
    }

    @Override
    public void apply(int viewDistance) {
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("viewDistance must be within 2..32 chunks");
        }
        targetViewDistance = viewDistance;
        for (Player player : server.getOnlinePlayers()) {
            applyTo(player);
        }
    }

    @Override
    public void restore() {
        targetViewDistance = -1;
        for (Player player : server.getOnlinePlayers()) {
            Integer original = originalViewDistances.remove(player.getUniqueId());
            if (original != null && player.getViewDistance() != original.intValue()) {
                player.setViewDistance(original.intValue());
            }
        }
        originalViewDistances.clear();
    }

    public int targetViewDistance() {
        return targetViewDistance;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        remember(player);
        server.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && targetViewDistance >= 2) {
                applyTo(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        originalViewDistances.remove(event.getPlayer().getUniqueId());
    }

    private void applyTo(Player player) {
        remember(player);
        if (player.getViewDistance() != targetViewDistance) {
            player.setViewDistance(targetViewDistance);
        }
    }

    private void remember(Player player) {
        originalViewDistances.computeIfAbsent(
                player.getUniqueId(),
                ignored -> Integer.valueOf(player.getViewDistance()));
    }
}
