package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.configs.RestrictionConfigPaths;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.language.MessageService;

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
        if (!isRegearingEnabled() || !isBlockBundleAccessEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!combatManager.isInCombat(player)) return;

        if (!isBundleInventory(event.getView())) return;

        // Cancel any interaction happening inside the bundle GUI so players can't take from / insert into it.
        if (event.getView().getTopInventory().equals(event.getClickedInventory())) {
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

        // Cancel any drag action into the bundle GUI.
        if (event.getView().getTopInventory().equals(event.getInventory())) {
            event.setCancelled(true);
            messageService.sendMessage(player, "combat_bundle_blocked");
        }
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