package org.enthusia.tempchallenges.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tempchallenges.application.ClaimService;
import org.enthusia.tempchallenges.application.EligibilityPolicy;
import org.enthusia.tempchallenges.application.OrderedChallengeProcessor;
import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.domain.Eligibility;
import org.enthusia.tempchallenges.domain.EventState;
import org.enthusia.tempchallenges.domain.SignalKey;
import org.enthusia.tempchallenges.domain.SignalType;
import org.enthusia.tempchallenges.domain.WinnerRecord;
import org.enthusia.tempchallenges.persistence.JdbcChallengeLedger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TempChallengesPlugin extends JavaPlugin {
    private ChallengeRegistry registry;
    private JdbcChallengeLedger ledger;
    private RewardCoordinator rewards;
    private FloodgateIdentityResolver identities;
    private ClaimService claimService;
    private String eventId;
    private EventState eventState;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            eventId = getConfig().getString("event.id", "frontier_2026_test");
            eventState = EventState.valueOf(getConfig().getString("event.state", "ACTIVE").toUpperCase(Locale.ROOT));
            registry = ChallengeRegistry.load(getConfig());
            Path database = getDataFolder().toPath().resolve(getConfig().getString("database.file", "challenge-ledger.sqlite"));
            ledger = new JdbcChallengeLedger(database);
            identities = new FloodgateIdentityResolver(getServer().getPluginManager());

            EligibilityPolicy eligibility = new EligibilityPolicy(
                    getConfig().getBoolean("eligibility.exclude-operators", true),
                    getConfig().getBoolean("eligibility.allow-adventure", false));
            long guardSeconds = Math.max(1, getConfig().getLong("eligibility.admin-mutation-guard-seconds", 10));
            AdminMutationGuard adminGuard = new AdminMutationGuard(this, Duration.ofSeconds(guardSeconds));
            DragonFightTracker dragonTracker = new DragonFightTracker(identities);
            rewards = new RewardCoordinator(this, ledger, registry, identities, eventId,
                    getConfig().getString("presentation.advancement-tree", "frontier_firsts"));
            OrderedChallengeProcessor processor = new OrderedChallengeProcessor(ledger);
            claimService = new ClaimService(processor, rewards,
                    exception -> getLogger().severe("Reward delivery failed after durable claim: " + exception.getMessage()));
            ChallengeSignalListener signals = new ChallengeSignalListener(this, registry, claimService, eligibility,
                    identities, adminGuard, dragonTracker, ledger, eventId, eventState,
                    getConfig().getString("eligibility.excluded-permission", "enthusia.tempchallenges.excluded"));

            getServer().getPluginManager().registerEvents(adminGuard, this);
            getServer().getPluginManager().registerEvents(dragonTracker, this);
            getServer().getPluginManager().registerEvents(signals, this);
            getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void join(org.bukkit.event.player.PlayerJoinEvent event) {
                    // A short delay lets optional network plugins finish their own join-time
                    // user loading before portable-entitlement reconciliation is attempted.
                    getServer().getScheduler().runTaskLater(TempChallengesPlugin.this,
                            () -> rewards.reconcile(event.getPlayer()), 20L);
                }
            }, this);

            Objects.requireNonNull(getCommand("challenges")).setExecutor((sender, command, label, args) -> {
                status(sender);
                return true;
            });
            Objects.requireNonNull(getCommand("tempchallenge")).setExecutor(this::adminCommand);
            getLogger().info("EnthusiaTempChallenges enabled: event=" + eventId + ", state=" + eventState +
                    ", challenges=" + registry.all().size() + ", Java 21 / Paper 1.21.11 target.");
        } catch (Exception exception) {
            getLogger().severe("Could not enable durable Frontier Firsts: " + exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (ledger != null) {
            try { ledger.close(); } catch (Exception ignored) { }
        }
    }

    private boolean adminCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            status(sender);
            return true;
        }
        if (!sender.hasPermission("enthusia.tempchallenges.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "export" -> {
                    Path file = getDataFolder().toPath().resolve("exports/frontier-first-winners.csv");
                    ledger.exportCsv(eventId, file);
                    sender.sendMessage("§aExported to " + file);
                }
                case "verify" -> verify(sender, args);
                case "reconcile" -> reconcile(sender, args);
                case "revoke-first" -> revoke(sender, args);
                case "award-first" -> awardFirst(sender, args);
                case "test" -> dryRun(sender, args);
                default -> usage(sender);
            }
        } catch (Exception ex) {
            getLogger().warning("Command failed: " + ex);
            sender.sendMessage("§cCommand failed; see console.");
        }
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§e/tempchallenge status|verify <id> [player]|export|reconcile <player>|revoke-first <id> <winner-uuid>|award-first <id> <player> CONFIRM <reason>|test <id> <player>");
    }

    private void reconcile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e/tempchallenge reconcile <online-player>");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage("§cPlayer must be online.");
            return;
        }
        rewards.reconcile(player);
        sender.sendMessage("§aReconciled local-winner rewards and portable presentation for " + player.getName() + '.');
    }

    private void status(CommandSender sender) {
        try {
            Map<String, WinnerRecord> winners = ledger == null ? Map.of() : ledger.winners(eventId);
            sender.sendMessage("§6Frontier Firsts §7(event=" + eventId + ", state=" + eventState + ")");
            for (ChallengeDefinition challenge : registry.all()) {
                WinnerRecord winner = winners.get(challenge.id());
                String state = challenge.locked() ? "§cLOCKED§7 — " + challenge.lockReason() :
                        winner == null ? "§aOPEN" : "§6" + winner.name() + " §7(" + winner.uuid() + ")";
                sender.sendMessage("§8- §e" + challenge.title() + "§8: " + state);
            }
        } catch (Exception ex) {
            sender.sendMessage("§cCould not read ledger: " + ex.getMessage());
        }
    }

    private void verify(CommandSender sender, String[] args) throws Exception {
        if (args.length < 2) {
            sender.sendMessage("§e/tempchallenge verify <id> [online-player]");
            return;
        }
        ChallengeDefinition challenge = registry.get(args[1]);
        if (challenge == null) {
            sender.sendMessage("§cUnknown challenge.");
            return;
        }
        Optional<WinnerRecord> winner = ledger.winner(eventId, challenge.id());
        sender.sendMessage(winner.map(value -> "§aWinner: " + value.name() + " / " + value.uuid() + " / " + value.claimedAt())
                .orElse("§eNo winner recorded."));
        if (args.length >= 3) {
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage("§cPlayer must be online for entitlement verification.");
                return;
            }
            ActorIdentity identity = identities.resolve(player);
            sender.sendMessage("§7Canonical UUID: " + identity.uuid() + " (" + identity.platform() + ")");
            sender.sendMessage("§7Explicit LuckPerms entitlement: " +
                    rewards.hasExplicitPortableEntitlement(identity.uuid(), challenge.permission()));
        }
    }

    private void revoke(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) {
            sender.sendMessage("§e/tempchallenge revoke-first <id> <expected-winner-uuid>");
            return;
        }
        ChallengeDefinition challenge = registry.get(args[1]);
        if (challenge == null) {
            sender.sendMessage("§cUnknown challenge.");
            return;
        }
        UUID expected = UUID.fromString(args[2]);
        boolean revoked = ledger.revoke(eventId, challenge.id(), expected, sender.getName(), "manual verified revocation");
        if (!revoked) {
            sender.sendMessage("§cWinner mismatch or no winner; nothing changed.");
            return;
        }
        rewards.revokePortable(expected, challenge);
        sender.sendMessage("§aRevoked durable first, explicit LuckPerms node, and online tag/advancement projections. Previously granted XP is not subtracted.");
    }

    private void awardFirst(CommandSender sender, String[] args) {
        if (args.length < 5 || !args[3].equalsIgnoreCase("CONFIRM")) {
            sender.sendMessage("§e/tempchallenge award-first <id> <online-player> CONFIRM <reason>");
            sender.sendMessage("§7This deliberately bypasses normal player eligibility, but never bypasses a locked challenge or closed event.");
            return;
        }
        ChallengeDefinition challenge = registry.get(args[1]);
        Player player = Bukkit.getPlayerExact(args[2]);
        if (challenge == null || player == null) {
            sender.sendMessage("§cUnknown challenge or player offline.");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).trim();
        if (reason.isBlank()) {
            sender.sendMessage("§cA review reason is required.");
            return;
        }
        ActorIdentity actor = identities.resolve(player);
        SignalKey signal = new SignalKey(SignalType.STAFF_OVERRIDE, challenge.id());
        ChallengeAttempt attempt = new ChallengeAttempt(eventId, challenge, actor,
                "staff-override:" + challenge.id() + ':' + actor.uuid() + ':' + System.nanoTime(),
                signal, "manual override by " + sender.getName() + ": " + reason,
                Instant.now(), Eligibility.allow(), eventState);
        ClaimResult result = claimService.handle(attempt);
        if (result.claimed()) {
            sender.sendMessage("§aDurably awarded " + challenge.id() + " to " + actor.name() + " through the explicit staff override path.");
        } else {
            sender.sendMessage("§eOverride not awarded: " + result.decision() + " — " + result.message());
        }
    }

    private void dryRun(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§e/tempchallenge test <id> <online-player>");
            return;
        }
        ChallengeDefinition challenge = registry.get(args[1]);
        Player player = Bukkit.getPlayerExact(args[2]);
        if (challenge == null || player == null) {
            sender.sendMessage("§cUnknown challenge or player offline.");
            return;
        }
        sender.sendMessage("§eDRY RUN ONLY — no claim will be written.");
        sender.sendMessage("§7Challenge: " + challenge.id() + ", locked=" + challenge.locked() + ", signals=" + challenge.signals());
        sender.sendMessage("§7Player canonical identity: " + identities.resolve(player));
    }
}
