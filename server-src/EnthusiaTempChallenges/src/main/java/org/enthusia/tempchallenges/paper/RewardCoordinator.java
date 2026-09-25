package org.enthusia.tempchallenges.paper;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tempchallenges.application.RewardSink;
import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.WinnerRecord;
import org.enthusia.tempchallenges.persistence.ChallengeLedger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RewardCoordinator implements RewardSink {
    private final JavaPlugin plugin;
    private final ChallengeLedger ledger;
    private final ChallengeRegistry registry;
    private final FloodgateIdentityResolver identities;
    private final String eventId;
    private final String advancementTree;

    public RewardCoordinator(JavaPlugin plugin, ChallengeLedger ledger, ChallengeRegistry registry,
                             FloodgateIdentityResolver identities, String eventId, String advancementTree) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.registry = registry;
        this.identities = identities;
        this.eventId = eventId;
        this.advancementTree = advancementTree;
    }

    @Override
    public void deliver(ChallengeAttempt attempt, WinnerRecord winner) {
        Bukkit.broadcastMessage("§6§l[FRONTIER FIRST] §e" + winner.name() + " §7was first to " +
                attempt.challenge().announcement() + "!");
        Player player = findOnlineByCanonical(winner.uuid());
        if (player != null && attempt.challenge().xp() > 0) player.giveExp(attempt.challenge().xp());
        ensurePortable(attempt.challenge(), winner.uuid());
        if (player != null) ensurePresentation(attempt.challenge(), player);
    }

    public void reconcile(Player player) {
        ActorIdentity identity = identities.resolve(player);
        for (ChallengeDefinition challenge : registry.all()) {
            try {
                boolean localWinner = ledger.winner(eventId, challenge.id())
                        .map(w -> w.uuid().equals(identity.uuid())).orElse(false);
                if (localWinner || hasExplicitPortableEntitlement(identity.uuid(), challenge.permission())) {
                    ensurePortable(challenge, identity.uuid());
                    ensurePresentation(challenge, player);
                }
            } catch (SQLException exception) {
                plugin.getLogger().warning("Reward reconciliation failed for " + challenge.id() + ": " + exception.getMessage());
            }
        }
    }

    public Player findOnlineByCanonical(UUID uuid) {
        Player direct = Bukkit.getPlayer(uuid);
        if (direct != null && identities.resolve(direct).uuid().equals(uuid)) return direct;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (identities.resolve(online).uuid().equals(uuid)) return online;
        }
        return null;
    }

    public boolean hasExplicitPortableEntitlement(UUID uuid, String permission) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return false;
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(uuid);
            if (user == null) return false;
            for (Node node : user.getNodes()) {
                if (node instanceof PermissionNode p && p.getPermission().equalsIgnoreCase(permission)
                        && p.getValue() && p.getContexts().isEmpty()) return true;
            }
        } catch (IllegalStateException ignored) { }
        return false;
    }

    private void ensurePortable(ChallengeDefinition challenge, UUID uuid) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            mark(challenge, "LUCKPERMS", "PENDING", "LuckPerms unavailable");
            return;
        }
        try {
            LuckPerms lp = LuckPermsProvider.get();
            lp.getUserManager().loadUser(uuid).thenAccept(user -> {
                try {
                    PermissionNode node = PermissionNode.builder(challenge.permission()).value(true).build();
                    user.data().add(node);
                    lp.getUserManager().saveUser(user).whenComplete((ignored, error) -> {
                        if (error == null) mark(challenge, "LUCKPERMS", "DELIVERED", null);
                        else mark(challenge, "LUCKPERMS", "PENDING", error.getMessage());
                    });
                } catch (RuntimeException ex) {
                    mark(challenge, "LUCKPERMS", "PENDING", ex.getMessage());
                }
            }).exceptionally(error -> {
                mark(challenge, "LUCKPERMS", "PENDING", error.getMessage());
                return null;
            });
        } catch (RuntimeException ex) {
            mark(challenge, "LUCKPERMS", "PENDING", ex.getMessage());
        }
    }

    private void ensurePresentation(ChallengeDefinition challenge, Player player) {
        if (!challenge.tag().isBlank()) {
            if (Bukkit.getPluginManager().isPluginEnabled("EnthusiaTags")) {
                boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "tag give " + player.getName() + " " + challenge.tag());
                mark(challenge, "TAG", accepted ? "DELIVERED" : "PENDING", accepted ? null : "tag command rejected");
            } else {
                mark(challenge, "TAG", "PENDING", "EnthusiaTags unavailable");
            }
        }
        if (!challenge.advancementNode().isBlank()) {
            if (Bukkit.getPluginManager().isPluginEnabled("EnthusiaAdvancements")) {
                boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "advancements grant " + player.getName() + " " + advancementTree + " " + challenge.advancementNode());
                mark(challenge, "ADVANCEMENT", accepted ? "DELIVERED" : "PENDING", accepted ? null : "advancement command rejected");
            } else {
                mark(challenge, "ADVANCEMENT", "PENDING", "EnthusiaAdvancements unavailable");
            }
        }
    }

    public void revokePortable(UUID uuid, ChallengeDefinition challenge) {
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                LuckPerms lp = LuckPermsProvider.get();
                lp.getUserManager().loadUser(uuid).thenAccept(user -> {
                    List<Node> remove = new ArrayList<>();
                    for (Node node : user.getNodes()) {
                        if (node instanceof PermissionNode p && p.getPermission().equalsIgnoreCase(challenge.permission())
                                && p.getContexts().isEmpty()) remove.add(node);
                    }
                    remove.forEach(user.data()::remove);
                    lp.getUserManager().saveUser(user);
                });
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Could not revoke LuckPerms entitlement: " + ex.getMessage());
            }
        }
        Player onlinePlayer = findOnlineByCanonical(uuid);
        if (onlinePlayer != null) {
            if (Bukkit.getPluginManager().isPluginEnabled("EnthusiaTags") && !challenge.tag().isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "tag revoke " + onlinePlayer.getName() + " " + challenge.tag());
            }
            if (Bukkit.getPluginManager().isPluginEnabled("EnthusiaAdvancements") && !challenge.advancementNode().isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "advancements revoke " + onlinePlayer.getName() + " " + advancementTree + " " + challenge.advancementNode());
            }
        }
    }

    private void mark(ChallengeDefinition challenge, String kind, String state, String error) {
        try {
            ledger.markReward(eventId, challenge.id(), kind, state, error);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not persist reward state: " + ex.getMessage());
        }
    }
}
