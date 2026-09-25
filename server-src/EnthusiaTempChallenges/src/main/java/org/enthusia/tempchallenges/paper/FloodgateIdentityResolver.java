package org.enthusia.tempchallenges.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.UUID;

public final class FloodgateIdentityResolver {
    private final PluginManager plugins;

    public FloodgateIdentityResolver(PluginManager plugins) {
        this.plugins = plugins;
    }

    public ActorIdentity resolve(Player player) {
        if (!plugins.isPluginEnabled("Floodgate")) {
            return new ActorIdentity(player.getUniqueId(), player.getName(), ActorIdentity.Platform.JAVA);
        }
        try {
            FloodgatePlayer floodgate = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
            if (floodgate == null) {
                return new ActorIdentity(player.getUniqueId(), player.getName(), ActorIdentity.Platform.JAVA);
            }
            UUID correct = floodgate.getCorrectUniqueId();
            UUID canonical = canonicalUuid(player.getUniqueId(), correct);
            String correctName = floodgate.getCorrectUsername();
            return new ActorIdentity(canonical,
                    correctName == null || correctName.isBlank() ? player.getName() : correctName,
                    correct != null && !correct.equals(player.getUniqueId())
                            ? ActorIdentity.Platform.BEDROCK_LINKED : ActorIdentity.Platform.BEDROCK);
        } catch (RuntimeException ignored) {
            return new ActorIdentity(player.getUniqueId(), player.getName(), ActorIdentity.Platform.JAVA);
        }
    }

    public static UUID canonicalUuid(UUID serverUuid, UUID floodgateCorrectUuid) {
        return floodgateCorrectUuid == null ? serverUuid : floodgateCorrectUuid;
    }
}
