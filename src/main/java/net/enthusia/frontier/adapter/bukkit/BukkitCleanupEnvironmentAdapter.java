package net.enthusia.frontier.adapter.bukkit;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.frontier.application.CleanupEnvironmentPort;
import net.enthusia.frontier.domain.ChunkKey;
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
        if (world.isChunkLoaded(key.x(), key.z()) || world.isChunkForceLoaded(key.x(), key.z())) {
            return false;
        }
        for (Player player : world.getPlayers()) {
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            if (Math.abs((long) playerChunkX - key.x()) <= minimumPlayerDistanceChunks
                    && Math.abs((long) playerChunkZ - key.z()) <= minimumPlayerDistanceChunks) {
                return false;
            }
        }
        return true;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return new UUID(0L, 0L);
        }
    }
}
