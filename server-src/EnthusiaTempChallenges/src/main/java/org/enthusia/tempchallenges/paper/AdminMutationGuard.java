package org.enthusia.tempchallenges.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminMutationGuard implements Listener {
    private final long guardMillis;
    private final Map<UUID, Long> guardedUntil = new ConcurrentHashMap<>();

    public AdminMutationGuard(Duration duration) {
        guardMillis = Math.max(1000L, duration.toMillis());
    }

    public boolean isGuarded(UUID uuid) {
        Long until = guardedUntil.get(uuid);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            guardedUntil.remove(uuid, until);
            return false;
        }
        return true;
    }

    @EventHandler public void serverCommand(ServerCommandEvent event) { inspect(event.getCommand()); }
    @EventHandler public void playerCommand(PlayerCommandPreprocessEvent event) { inspect(event.getMessage()); }

    private void inspect(String raw) {
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 2) return;
        String root = parts[0].toLowerCase();
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);

        String target = null;
        if (root.equals("give")) target = parts[1];
        else if (root.equals("advancement") && parts.length >= 3 && parts[1].equalsIgnoreCase("grant")) target = parts[2];
        else if (root.equals("item") && parts.length >= 4 && parts[1].equalsIgnoreCase("replace") && parts[2].equalsIgnoreCase("entity")) target = parts[3];
        if (target == null) return;

        if (target.startsWith("@")) {
            for (Player player : Bukkit.getOnlinePlayers()) guard(player.getUniqueId());
            return;
        }
        Player exact = Bukkit.getPlayerExact(target);
        if (exact != null) guard(exact.getUniqueId());
    }

    private void guard(UUID uuid) {
        guardedUntil.put(uuid, System.currentTimeMillis() + guardMillis);
    }
}
