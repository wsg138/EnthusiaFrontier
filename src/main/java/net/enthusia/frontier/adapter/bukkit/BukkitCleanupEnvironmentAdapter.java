package net.enthusia.frontier.adapter.bukkit;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.frontier.application.CleanupEnvironmentPort;
import net.enthusia.frontier.config.GenerationShieldSettings;
import net.enthusia.frontier.domain.ChunkKey;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Immediate Bukkit safety checks performed on the server thread before deletion. */
public final class BukkitCleanupEnvironmentAdapter implements CleanupEnvironmentPort {
    private final Server server;

    public BukkitCleanupEnvironmentAdapter(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public double recentMspt() {
        return server.getAverageTickTime();
    }

    @Override
    public boolean isSafeToClear(String worldName, ChunkKey key, int minimumPlayerDistanceChunks) {
        World world = server.getWorld(Objects.requireNonNull(worldName, "worldName"));
        if (world == null || !world.getUID().equals(parseUuid(key.worldUuid()))) {
            return false;
        }
        if (world.isChunkLoaded(key.x(), key.z())
                || world.isChunkForceLoaded(key.x(), key.z())
                || !world.getPluginChunkTickets(key.x(), key.z()).isEmpty()) {
            return false;
        }
        for (Player player : world.getPlayers()) {
            Location location = player.getLocation();
            int playerChunkX = location.getBlockX() >> 4;
            int playerChunkZ = location.getBlockZ() >> 4;
            int exclusionRadius = requiredPlayerExclusionRadius(player, minimumPlayerDistanceChunks);
            if (Math.abs((long) playerChunkX - key.x()) <= exclusionRadius
                    && Math.abs((long) playerChunkZ - key.z()) <= exclusionRadius) {
                return false;
            }
        }
        return true;
    }

    static int requiredPlayerExclusionRadius(Player player, int minimumPlayerDistanceChunks) {
        Objects.requireNonNull(player, "player");
        if (minimumPlayerDistanceChunks < 0) {
            throw new IllegalArgumentException("minimumPlayerDistanceChunks must be >= 0");
        }
        int runtimeDistance = Math.max(
                Math.max(player.getViewDistance(), player.getSendViewDistance()),
                player.getSimulationDistance());
        int moonriseFootprint = Math.addExact(
                runtimeDistance,
                GenerationShieldSettings.MINIMUM_MOONRISE_GUARD_OVERHEAD_CHUNKS);
        return Math.max(minimumPlayerDistanceChunks, moonriseFootprint);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return new UUID(0L, 0L);
        }
    }
}
