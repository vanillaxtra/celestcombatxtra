package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.CelestCombatXtra;
import com.shyamstudio.celestcombatXtra.configs.RestrictionConfigPaths;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.language.MessageService;

import java.util.List;
import java.util.Locale;

public class EnderchestLister implements Listener {

    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    private final MessageService messageService;

    public EnderchestLister(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.combatManager = plugin.getCombatManager();
        this.messageService = plugin.getMessageService();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isRegearingEnabled()) return;

        Player player = event.getPlayer();
        if (!combatManager.isInCombat(player)) return;

        ItemStack handItem = event.getItem();
        // Bundles: allow opening/moving; block take/put inside the bundle GUI in InventoryClickEvent/InventoryDragEvent.

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }
        if (clickedBlock.getType() == Material.ENDER_CHEST && isBlockEnderchestEnabled()) {
            event.setCancelled(true);
            messageService.sendMessage(player, "combat_enderchest_blocked");
            return;
        }

        if (Tag.SHULKER_BOXES.isTagged(clickedBlock.getType()) && isBlockShulkerBoxesEnabled()) {
            event.setCancelled(true);
            messageService.sendMessage(player, "combat_shulker_blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isRegearingEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (!isBundleInventory(event.getView())) return;
        if (!event.getView().getTopInventory().equals(event.getClickedInventory())) return;

        // Block all bundle access when in combat (if enabled)
        if (isBlockBundleAccessEnabled() && combatManager.isInCombat(player)) {
            event.setCancelled(true);
            messageService.sendMessage(player, "combat_bundle_blocked");
            return;
        }

        // Block taking (left/right click) items that are restricted
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;

        int amountToTake = event.getClick() == ClickType.RIGHT ? Math.max(1, (current.getAmount() + 1) / 2) : current.getAmount();
        if (isItemBlockedForTake(player, current.getType(), amountToTake)) {
            event.setCancelled(true);
            messageService.sendMessage(player, "combat_bundle_blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isRegearingEnabled() || !isBlockBundleAccessEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!combatManager.isInCombat(player)) return;

        if (!isBundleInventory(event.getView())) return;

        // Cancel any drag action involving the bundle GUI.
        if (event.getView().getTopInventory().equals(event.getInventory())) {
            event.setCancelled(true);
            messageService.sendMessage(player, "combat_bundle_blocked");
        }
    }

    /** Returns true if the player cannot take this material from a bundle (disabled in combat, cooldown, or over limit). */
    private boolean isItemBlockedForTake(Player player, Material material, int amountToTake) {
        if (material == null || material.isAir() || amountToTake <= 0) return false;

        // Combat disabled items
        if (combatManager.isInCombat(player) && isItemDisabledInCombat(material)) {
            return true;
        }

        // Ender pearl on cooldown
        if (material == Material.ENDER_PEARL && combatManager.isEnderPearlOnCooldown(player)) {
            return true;
        }

        // Trident on cooldown
        if (material == Material.TRIDENT && combatManager.isTridentOnCooldown(player)) {
            return true;
        }

        // Wind charge on cooldown (CelestCombatXtra)
        if (material == Material.WIND_CHARGE && plugin instanceof CelestCombatXtra xtra) {
            var icm = xtra.getItemCooldownManager();
            if (icm != null && icm.isWindChargeOnCooldown(player)) {
                return true;
            }
        }

        // Item limiter would exceed (CelestCombatXtra)
        if (plugin instanceof CelestCombatXtra xtra) {
            var ill = xtra.getItemLimiterListener();
            if (ill != null && ill.wouldExceedLimit(player, material, amountToTake)) {
                return true;
            }
        }

        return false;
    }

    private boolean isItemDisabledInCombat(Material material) {
        String root = RestrictionConfigPaths.itemRestrictionsRoot(plugin.getConfig());
        if (!plugin.getConfig().getBoolean(root + ".disabled_items.enabled", true)) return false;
        List<String> disabled = plugin.getConfig().getStringList(root + ".disabled_items.items");
        if (disabled.isEmpty()) disabled = plugin.getConfig().getStringList(root + ".disabled_items");
        if (disabled == null || disabled.isEmpty()) return false;
        String matName = material.name();
        for (String d : disabled) {
            if (d != null && (matName.equalsIgnoreCase(d) || matName.contains(d.toUpperCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    private String regearingRoot() {
        return RestrictionConfigPaths.regearingRoot(plugin.getConfig());
    }

    private boolean isRegearingEnabled() {
        return plugin.getConfig().getBoolean(regearingRoot() + ".enabled", true);
    }

    private boolean isBlockEnderchestEnabled() {
        String rg = regearingRoot();
        return plugin.getConfig().getBoolean(rg + ".block_enderchest",
                plugin.getConfig().getBoolean("combat.block_enderchest", true));
    }

    private boolean isBlockShulkerBoxesEnabled() {
        return plugin.getConfig().getBoolean(regearingRoot() + ".block_shulker_boxes", true);
    }

    private boolean isBlockBundleAccessEnabled() {
        return plugin.getConfig().getBoolean(regearingRoot() + ".block_bundle_access", true);
    }

    private boolean isBundle(ItemStack item) {
        return item != null && item.getType() == Material.BUNDLE;
    }

    private boolean isBundleInventory(org.bukkit.inventory.InventoryView view) {
        if (view == null) return false;

        // Bundle inventory titles vary by client/version; keep detection lightweight.
        String title = view.getTitle();
        if (title != null && title.toLowerCase().contains("bundle")) return true;

        if (view.getTopInventory() != null && view.getTopInventory().getHolder() != null) {
            String holderName = view.getTopInventory().getHolder().getClass().getSimpleName();
            return holderName != null && holderName.toLowerCase().contains("bundle");
        }
        return false;
    }
}