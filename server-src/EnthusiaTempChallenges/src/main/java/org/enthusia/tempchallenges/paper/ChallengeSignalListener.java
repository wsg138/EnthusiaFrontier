package org.enthusia.tempchallenges.paper;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tempchallenges.application.ClaimService;
import org.enthusia.tempchallenges.application.EligibilityPolicy;
import org.enthusia.tempchallenges.domain.ActorIdentity;
import org.enthusia.tempchallenges.domain.ChallengeAttempt;
import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.ClaimDecision;
import org.enthusia.tempchallenges.domain.ClaimResult;
import org.enthusia.tempchallenges.domain.DragonContribution;
import org.enthusia.tempchallenges.domain.Eligibility;
import org.enthusia.tempchallenges.domain.EventState;
import org.enthusia.tempchallenges.domain.PlayerContext;
import org.enthusia.tempchallenges.domain.SignalKey;
import org.enthusia.tempchallenges.domain.SignalType;
import org.enthusia.tempchallenges.persistence.ChallengeLedger;

import java.time.Instant;
import java.util.Collection;

/**
 * Converts only trusted gameplay transitions into first-acquisition evidence.
 * Generic inventory possession is intentionally not evidence: rollback restores,
 * staff-created items, plugin-filled containers and player-to-player handoffs must
 * never manufacture a server-first after the fact.
 */
public final class ChallengeSignalListener implements Listener {
    private final JavaPlugin plugin;
    private final ChallengeRegistry registry;
    private final ClaimService claims;
    private final EligibilityPolicy eligibility;
    private final FloodgateIdentityResolver identities;
    private final AdminMutationGuard adminGuard;
    private final DragonFightTracker dragonTracker;
    private final ChallengeLedger ledger;
    private final String eventId;
    private final EventState eventState;
    private final String excludedPermission;
    private final NamespacedKey testItemKey;
    private final NamespacedKey trustedNaturalOriginKey;
    private final NamespacedKey invalidSpawnOriginKey;

    public ChallengeSignalListener(JavaPlugin plugin, ChallengeRegistry registry, ClaimService claims,
                                   EligibilityPolicy eligibility, FloodgateIdentityResolver identities,
                                   AdminMutationGuard adminGuard, DragonFightTracker dragonTracker,
                                   ChallengeLedger ledger, String eventId, EventState eventState,
                                   String excludedPermission) {
        this.plugin = plugin;
        this.registry = registry;
        this.claims = claims;
        this.eligibility = eligibility;
        this.identities = identities;
        this.adminGuard = adminGuard;
        this.dragonTracker = dragonTracker;
        this.ledger = ledger;
        this.eventId = eventId;
        this.eventState = eventState;
        this.excludedPermission = excludedPermission;
        this.testItemKey = new NamespacedKey(plugin, "challenge-test-item");
        this.trustedNaturalOriginKey = new NamespacedKey(plugin, "trusted-natural-origin");
        this.invalidSpawnOriginKey = new NamespacedKey(plugin, "invalid-spawn-origin");
    }

    @EventHandler
    public void advancement(PlayerAdvancementDoneEvent event) {
        String key = event.getAdvancement().getKey().toString();
        emit(event.getPlayer(), new SignalKey(SignalType.VANILLA_ADVANCEMENT, key),
                "adv:" + identities.resolve(event.getPlayer()).uuid() + ':' + key, "vanilla advancement");
    }

    @EventHandler
    public void world(PlayerChangedWorldEvent event) {
        World.Environment env = event.getPlayer().getWorld().getEnvironment();
        emit(event.getPlayer(), new SignalKey(SignalType.WORLD_ENTRY, env.name()),
                "world:" + identities.resolve(event.getPlayer()).uuid() + ':' + env.name(), "world entry");
    }

    @EventHandler(ignoreCancelled = true)
    public void creatureSpawn(CreatureSpawnEvent event) {
        switch (event.getSpawnReason()) {
            case COMMAND, CUSTOM, SPAWNER_EGG, DISPENSE_EGG ->
                    event.getEntity().getPersistentDataContainer().set(invalidSpawnOriginKey, PersistentDataType.BYTE, (byte) 1);
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void generatedLoot(LootGenerateEvent event) {
        if (event.isPlugin()) return;
        for (ItemStack item : event.getLoot()) markTrustedNaturalOrigin(item);
    }

    @EventHandler(ignoreCancelled = true)
    public void blockDrops(BlockDropItemEvent event) {
        event.getItems().forEach(entity -> {
            ItemStack item = entity.getItemStack();
            if (markTrustedNaturalOrigin(item)) entity.setItemStack(item);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (invalidItem(item) || !consumeTrustedNaturalOrigin(item)) return;
        event.getItem().setItemStack(item);
        emitItem(player, item.getType(), "pickup:" + event.getItem().getUniqueId(), "trusted natural item-entity pickup");
    }

    @EventHandler(ignoreCancelled = true)
    public void craft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) result = event.getRecipe().getResult();
        if (invalidItem(result)) return;
        emitItem(player, result.getType(), perTick(player, "craft", result.getType()), "craft result");
    }

    @EventHandler(ignoreCancelled = true)
    public void smith(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (result != null && !result.getType().isAir() && !invalidItem(result)) {
            emitItem(player, result.getType(), perTick(player, "smith", result.getType()), "smithing result");
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> checkNetheriteArmor(player));
    }

    @EventHandler(ignoreCancelled = true)
    public void furnace(FurnaceExtractEvent event) {
        ItemStack item = event.getItemStack();
        if (invalidItem(item)) return;
        emitItem(event.getPlayer(), item.getType(),
                perTick(event.getPlayer(), "furnace", item.getType()), "furnace extraction");
    }

    @EventHandler(ignoreCancelled = true)
    public void container(InventoryClickEvent event) {
        if (event instanceof CraftItemEvent || event instanceof SmithItemEvent) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        InventoryAction action = event.getAction();
        if (!(action == InventoryAction.PICKUP_ALL || action == InventoryAction.PICKUP_HALF ||
                action == InventoryAction.PICKUP_ONE || action == InventoryAction.PICKUP_SOME ||
                action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.HOTBAR_SWAP)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir() || invalidItem(item) || !consumeTrustedNaturalOrigin(item)) return;
        event.setCurrentItem(item);
        emitItem(player, item.getType(), perTick(player, "natural-loot:" + event.getRawSlot(), item.getType()),
                "trusted non-plugin loot-container transfer");
    }

    @EventHandler
    public void death(EntityDeathEvent event) {
        boolean invalidSpawn = isInvalidSpawn(event);
        for (ItemStack drop : event.getDrops()) {
            if (invalidSpawn) adminGuard.markInvalidOrigin(drop);
            else markTrustedNaturalOrigin(drop);
        }

        Player killer = event.getEntity().getKiller();
        if (event.getEntity() instanceof EnderDragon dragon) {
            Collection<DragonContribution> contributions = dragonTracker.finish(dragon.getUniqueId());
            try {
                ledger.saveDragonSession(eventId, dragon.getUniqueId(), contributions,
                        killer == null ? null : identities.resolve(killer).uuid());
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not save Dragon contribution session: " + ex.getMessage());
            }
            if (!invalidSpawn && killer != null) {
                emit(killer, new SignalKey(SignalType.ENTITY_FINAL_KILL, "ENDER_DRAGON"),
                        "kill:" + dragon.getUniqueId(), "credited final blow; contributors=" + contributions.size());
            }
        } else if (!invalidSpawn && event.getEntity() instanceof Wither wither && killer != null) {
            emit(killer, new SignalKey(SignalType.ENTITY_FINAL_KILL, "WITHER"),
                    "kill:" + wither.getUniqueId(), "credited final blow");
        }
    }

    private void checkNetheriteArmor(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        ItemStack chest = player.getInventory().getChestplate();
        ItemStack legs = player.getInventory().getLeggings();
        ItemStack boots = player.getInventory().getBoots();
        if (type(helmet) == Material.NETHERITE_HELMET && type(chest) == Material.NETHERITE_CHESTPLATE &&
                type(legs) == Material.NETHERITE_LEGGINGS && type(boots) == Material.NETHERITE_BOOTS &&
                !invalidItem(helmet) && !invalidItem(chest) && !invalidItem(legs) && !invalidItem(boots)) {
            emit(player, new SignalKey(SignalType.ARMOR_COMPLETE, "NETHERITE"),
                    perTick(player, "armor", Material.NETHERITE_CHESTPLATE),
                    "full netherite set after trusted smithing");
        }
    }

    private Material type(ItemStack item) { return item == null ? Material.AIR : item.getType(); }

    private void emitItem(Player player, Material material, String signalId, String detail) {
        emit(player, new SignalKey(SignalType.ITEM_ACQUIRED, material.name()), signalId, detail);
    }

    private void emit(Player player, SignalKey signal, String signalId, String detail) {
        ActorIdentity actor = identities.resolve(player);
        PlayerContext context = new PlayerContext(mode(player.getGameMode()), player.isOp(),
                !excludedPermission.isBlank() && player.hasPermission(excludedPermission),
                adminGuard.isGuarded(player.getUniqueId()));
        Eligibility eligibilityResult = eligibility.evaluate(context);
        for (ChallengeDefinition challenge : registry.route(signal)) {
            ChallengeAttempt attempt = new ChallengeAttempt(eventId, challenge, actor,
                    signalId + ':' + challenge.id(), signal, detail, Instant.now(), eligibilityResult, eventState);
            ClaimResult result = claims.handle(attempt);
            if (result.decision() == ClaimDecision.PERSISTENCE_FAILED) {
                plugin.getLogger().severe("Fail-closed persistence error for " + challenge.id() + ": " + result.message());
            }
        }
    }

    private boolean markTrustedNaturalOrigin(ItemStack item) {
        if (item == null || item.getType().isAir() || !hasItemChallenge(item.getType()) || invalidItem(item)) return false;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(trustedNaturalOriginKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return true;
    }

    private boolean consumeTrustedNaturalOrigin(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        Byte marker = meta.getPersistentDataContainer().get(trustedNaturalOriginKey, PersistentDataType.BYTE);
        if (marker == null || marker == 0) return false;
        meta.getPersistentDataContainer().remove(trustedNaturalOriginKey);
        item.setItemMeta(meta);
        return true;
    }

    private boolean hasItemChallenge(Material material) {
        return !registry.route(new SignalKey(SignalType.ITEM_ACQUIRED, material.name())).isEmpty();
    }

    private boolean isInvalidSpawn(EntityDeathEvent event) {
        Byte marker = event.getEntity().getPersistentDataContainer().get(invalidSpawnOriginKey, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    private boolean invalidItem(ItemStack item) {
        return isTestItem(item) || adminGuard.isInvalidOrigin(item);
    }

    private boolean isTestItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(testItemKey, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    private String perTick(Player player, String source, Material material) {
        return source + ':' + identities.resolve(player).uuid() + ':' + material.name() + ':' + (System.currentTimeMillis() / 50L);
    }

    private PlayerContext.Mode mode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> PlayerContext.Mode.SURVIVAL;
            case ADVENTURE -> PlayerContext.Mode.ADVENTURE;
            case CREATIVE -> PlayerContext.Mode.CREATIVE;
            case SPECTATOR -> PlayerContext.Mode.SPECTATOR;
        };
    }
}
