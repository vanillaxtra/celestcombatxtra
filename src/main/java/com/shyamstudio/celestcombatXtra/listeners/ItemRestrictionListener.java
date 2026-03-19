package com.shyamstudio.celestcombatXtra.listeners;

import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.configs.RestrictionConfigPaths;
import com.shyamstudio.celestcombatXtra.cooldown.UseCooldowns;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class ItemRestrictionListener implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    public static String formatItemName(Material material) {
        if (material == null) {
            return "Unknown Item";
        }

        // Convert from UPPERCASE_WITH_UNDERSCORES to Title Case
        String[] words = material.name().split("_");
        StringBuilder formattedName = new StringBuilder();

        for (String word : words) {
            // Capitalize first letter, rest lowercase
            formattedName
                    .append(word.substring(0, 1).toUpperCase())
                    .append(word.substring(1).toLowerCase())
                    .append(" ");
        }

        return formattedName.toString().trim();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (!isDisabledItemsEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (combatManager.isInCombat(player)) {
            List<String> disabledItems = getDisabledItemsList();

            // Check if the consumed item is in the disabled items list
            if (isItemDisabled(item.getType(), disabledItems)) {
                event.setCancelled(true);
                UseCooldowns.applyUseCooldownIfKnown(player, item.getType());

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", formatItemName(item.getType()));
                sendRestrictionActionBar(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMoveEvent(PlayerMoveEvent event) {
        if (!isDisabledItemsEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        if (combatManager.isInCombat(player)) {
            List<String> disabledItems = getDisabledItemsList();

            if (disabledItems.contains("ELYTRA") && player.isGliding()) {
                player.setGliding(false);
                UseCooldowns.applyUseCooldownIfKnown(player, Material.ELYTRA);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", "Elytra");
                sendRestrictionActionBar(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    private boolean isItemDisabled(Material itemType, List<String> disabledItems) {
        return disabledItems.stream()
                .anyMatch(disabledItem ->
                        itemType.name().equalsIgnoreCase(disabledItem) ||
                                itemType.name().contains(disabledItem)
                );
    }

    private String itemRestrictionsRoot() {
        return RestrictionConfigPaths.itemRestrictionsRoot(plugin.getConfig());
    }

    private List<String> getDisabledItemsList() {
        String root = itemRestrictionsRoot();
        List<String> disabledItems = plugin.getConfig().getStringList(root + ".disabled_items.items");
        if (disabledItems.isEmpty()) {
            disabledItems = plugin.getConfig().getStringList(root + ".disabled_items");
        }
        return disabledItems;
    }

    private boolean isDisabledItemsEnabled() {
        String root = itemRestrictionsRoot();
        boolean masterEnabled = plugin.getConfig().getBoolean(root + ".enabled", true);
        boolean sectionEnabled = plugin.getConfig().getBoolean(root + ".disabled_items.enabled", true);
        return masterEnabled && sectionEnabled;
    }

    private void sendRestrictionActionBar(Player player, String key, Map<String, String> placeholders) {
        if (player == null || key == null) return;
        String actionBar = plugin.getLanguageManager().getActionBar(key, placeholders);
        if (actionBar != null) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionBar));
        } else {
            plugin.getMessageService().sendMessage(player, key, placeholders);
        }
    }
}