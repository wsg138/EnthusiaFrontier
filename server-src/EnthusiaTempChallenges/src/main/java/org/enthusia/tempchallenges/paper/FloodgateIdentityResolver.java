package org.enthusia.tempchallenges.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.enthusia.tempchallenges.domain.ActorIdentity;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Floodgate is a runtime soft dependency. Reflection is intentional here: the
 * challenge ledger must remain available for Java players even if Floodgate is
 * temporarily unavailable, while still using Floodgate's canonical linked UUID
 * whenever the backend API is present.
 */
public final class FloodgateIdentityResolver {
    private final PluginManager plugins;

    public FloodgateIdentityResolver(PluginManager plugins) {
        this.plugins = plugins;
    }

    public ActorIdentity resolve(Player player) {
        Plugin floodgate = plugins.getPlugin("floodgate");
        if (floodgate == null) floodgate = plugins.getPlugin("Floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return javaIdentity(player);
        }

        try {
            ClassLoader loader = floodgate.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object floodgatePlayer = apiClass.getMethod("getPlayer", UUID.class).invoke(api, player.getUniqueId());
            if (floodgatePlayer == null) return javaIdentity(player);

            Method correctUuidMethod = floodgatePlayer.getClass().getMethod("getCorrectUniqueId");
            Method correctNameMethod = floodgatePlayer.getClass().getMethod("getCorrectUsername");
            Method linkedMethod = floodgatePlayer.getClass().getMethod("isLinked");
            UUID canonical = canonicalUuid(player.getUniqueId(), (UUID) correctUuidMethod.invoke(floodgatePlayer));
            String correctName = (String) correctNameMethod.invoke(floodgatePlayer);
            boolean linked = Boolean.TRUE.equals(linkedMethod.invoke(floodgatePlayer));
            return new ActorIdentity(canonical,
                    correctName == null || correctName.isBlank() ? player.getName() : correctName,
                    linked ? ActorIdentity.Platform.BEDROCK_LINKED : ActorIdentity.Platform.BEDROCK);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return javaIdentity(player);
        }
    }

    public static UUID canonicalUuid(UUID serverUuid, UUID floodgateCorrectUuid) {
        return floodgateCorrectUuid == null ? serverUuid : floodgateCorrectUuid;
    }

    private ActorIdentity javaIdentity(Player player) {
        return new ActorIdentity(player.getUniqueId(), player.getName(), ActorIdentity.Platform.JAVA);
    }
}
