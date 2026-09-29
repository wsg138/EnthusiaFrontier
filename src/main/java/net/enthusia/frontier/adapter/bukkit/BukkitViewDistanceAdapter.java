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

/** Applies per-player view/send distance without changing simulation distance. */
public final class BukkitViewDistanceAdapter implements ViewDistancePort, Listener {
    private final JavaPlugin plugin;
    private final Server server;
    private final Map<UUID, OriginalDistances> originals = new HashMap<>();
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
            OriginalDistances original = originals.remove(player.getUniqueId());
            if (original != null) {
                player.setViewDistance(original.viewDistance());
                player.setSendViewDistance(original.sendViewDistance());
            }
        }
        originals.clear();
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
        originals.remove(event.getPlayer().getUniqueId());
    }

    private void applyTo(Player player) {
        remember(player);
        player.setViewDistance(targetViewDistance);
        player.setSendViewDistance(targetViewDistance);
    }

    private void remember(Player player) {
        originals.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new OriginalDistances(player.getViewDistance(), player.getSendViewDistance()));
    }

    private record OriginalDistances(int viewDistance, int sendViewDistance) {
    }
}
