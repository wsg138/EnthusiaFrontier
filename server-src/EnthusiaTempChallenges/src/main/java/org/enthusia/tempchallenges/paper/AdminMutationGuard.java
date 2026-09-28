package org.enthusia.tempchallenges.paper;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excludes explicit staff/creative item mutations from server-first evidence.
 * A persistent item marker follows the item through drops, chests and common
 * crafting/smelting/smithing transformations instead of relying only on a
 * short-lived player flag.
 */
public final class AdminMutationGuard implements Listener {
    private final JavaPlugin plugin;
    private final long guardMillis;
    private final NamespacedKey invalidOriginKey;
    private final Map<UUID, Long> guardedUntil = new ConcurrentHashMap<>();

    public AdminMutationGuard(JavaPlugin plugin, Duration duration) {
        this.plugin = plugin;
        this.guardMillis = Math.max(1000L, duration.toMillis());
        this.invalidOriginKey = new NamespacedKey(plugin, "invalid-first-origin");
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

    public boolean isInvalidOrigin(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(invalidOriginKey, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    public ItemStack markInvalidOrigin(ItemStack item) {
        if (item == null || item.getType().isAir()) return item;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(invalidOriginKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void serverCommand(ServerCommandEvent event) {
        inspect(event.getCommand());
    }

    @EventHandler
    public void playerCommand(PlayerCommandPreprocessEvent event) {
        inspect(event.getMessage());
    }

    @EventHandler(ignoreCancelled = true)
    public void creative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        guard(player.getUniqueId());
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) event.setCursor(markInvalidOrigin(cursor));
        ItemStack current = event.getCurrentItem();
        if (current != null && !current.getType().isAir()) event.setCurrentItem(markInvalidOrigin(current));
    }

    @EventHandler(ignoreCancelled = true)
    public void guardedInventoryMove(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isGuarded(player.getUniqueId()) && player.getGameMode() != GameMode.CREATIVE) return;
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) event.setCursor(markInvalidOrigin(cursor));
        ItemStack current = event.getCurrentItem();
        if (current != null && !current.getType().isAir()) event.setCurrentItem(markInvalidOrigin(current));
    }

    @EventHandler(ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (isGuarded(event.getPlayer().getUniqueId()) || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            event.getItemDrop().setItemStack(markInvalidOrigin(event.getItemDrop().getItemStack()));
        }
    }

    @EventHandler
    public void prepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack input : event.getInventory().getMatrix()) {
            if (isInvalidOrigin(input)) {
                ItemStack result = event.getInventory().getResult();
                if (result != null) event.getInventory().setResult(markInvalidOrigin(result));
                return;
            }
        }
    }

    @EventHandler
    public void prepareSmith(PrepareSmithingEvent event) {
        for (ItemStack input : event.getInventory().getContents()) {
            if (isInvalidOrigin(input)) {
                ItemStack result = event.getResult();
                if (result != null) event.setResult(markInvalidOrigin(result));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void furnaceSmelt(FurnaceSmeltEvent event) {
        if (isInvalidOrigin(event.getSource())) {
            event.setResult(markInvalidOrigin(event.getResult()));
        }
    }

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
            for (Player player : Bukkit.getOnlinePlayers()) guardAndSnapshot(player);
            return;
        }
        Player exact = Bukkit.getPlayerExact(target);
        if (exact != null) guardAndSnapshot(exact);
    }

    private void guardAndSnapshot(Player player) {
        guard(player.getUniqueId());
        ItemStack[] before = cloneContents(player.getInventory().getContents());
        plugin.getServer().getScheduler().runTask(plugin, () -> markIntroducedItems(player, before));
    }

    private void markIntroducedItems(Player player, ItemStack[] before) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] after = inventory.getContents();
        boolean changed = false;
        for (int i = 0; i < after.length; i++) {
            ItemStack previous = i < before.length ? before[i] : null;
            ItemStack current = after[i];
            if (introducedOrExpanded(previous, current)) {
                after[i] = markInvalidOrigin(current);
                changed = true;
            }
        }
        if (changed) inventory.setContents(after);
    }

    static boolean introducedOrExpanded(ItemStack before, ItemStack after) {
        if (after == null || after.getType().isAir()) return false;
        if (before == null || before.getType().isAir()) return true;
        if (!before.isSimilar(after)) return true;
        return after.getAmount() > before.getAmount();
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
        return copy;
    }

    private void guard(UUID uuid) {
        guardedUntil.put(uuid, System.currentTimeMillis() + guardMillis);
    }
}
