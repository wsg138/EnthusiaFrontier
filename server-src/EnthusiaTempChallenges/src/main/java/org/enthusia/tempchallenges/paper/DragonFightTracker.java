package org.enthusia.tempchallenges.paper;

import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.enthusia.tempchallenges.domain.DragonContribution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DragonFightTracker implements Listener {
    private final FloodgateIdentityResolver identities;
    private final Map<UUID, Map<UUID, Mutable>> fights = new ConcurrentHashMap<>();

    public DragonFightTracker(FloodgateIdentityResolver identities) {
        this.identities = identities;
    }

    @EventHandler(ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        Player player = playerDamager(event.getDamager());
        if (player == null) return;
        UUID canonical = identities.resolve(player).uuid();
        Instant now = Instant.now();
        fights.computeIfAbsent(dragon.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .compute(canonical, (id, current) -> {
                    if (current == null) current = new Mutable(now);
                    current.damage += Math.max(0.0, event.getFinalDamage());
                    current.hits++;
                    current.last = now;
                    return current;
                });
    }

    public Collection<DragonContribution> finish(UUID dragonUuid) {
        Map<UUID, Mutable> map = fights.remove(dragonUuid);
        if (map == null) return List.of();
        List<DragonContribution> result = new ArrayList<>();
        map.forEach((uuid, m) -> result.add(new DragonContribution(uuid, m.damage, m.hits, m.first, m.last)));
        return result;
    }

    private Player playerDamager(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private static final class Mutable {
        double damage;
        int hits;
        final Instant first;
        Instant last;
        Mutable(Instant at) { first = at; last = at; }
    }
}
