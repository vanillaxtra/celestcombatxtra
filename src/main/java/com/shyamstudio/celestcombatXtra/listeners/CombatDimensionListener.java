package com.shyamstudio.celestcombatXtra.listeners;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.language.MessageService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Blocks cross-world travel while combat-tagged, using blacklist or whitelist of destination world names.
 */
public class CombatDimensionListener implements Listener {

    private static final String BYPASS_PERM = "celestcombatxtra.bypass.dimension_change";

    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    private final MessageService messageService;

    public CombatDimensionListener(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.messageService = plugin.getMessageService();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (shouldBlock(event.getPlayer(), event.getFrom(), event.getTo())) {
            event.setCancelled(true);
            sendBlocked(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (shouldBlock(event.getPlayer(), event.getFrom(), event.getTo())) {
            event.setCancelled(true);
            sendBlocked(event.getPlayer(), event.getTo());
        }
    }

    private boolean shouldBlock(Player player, Location from, Location to) {
        if (!plugin.getConfig().getBoolean("combat.dimension_change.enabled", false)) {
            return false;
        }
        if (player == null || from == null || to == null) {
            return false;
        }
        World fromWorld = from.getWorld();
        World toWorld = to.getWorld();
        if (fromWorld == null || toWorld == null) {
            return false;
        }
        if (fromWorld.equals(toWorld)) {
            return false;
        }
        if (!combatManager.isInCombat(player)) {
            return false;
        }
        if (player.hasPermission(BYPASS_PERM)) {
            return false;
        }

        String dest = toWorld.getName();
        String mode = plugin.getConfig().getString("combat.dimension_change.mode", "blacklist");
        if (mode == null) mode = "blacklist";

        if ("whitelist".equalsIgnoreCase(mode)) {
            return !listContains(plugin.getConfig().getStringList("combat.dimension_change.allowed_worlds"), dest);
        }
        // blacklist (default)
        return listContains(plugin.getConfig().getStringList("combat.dimension_change.blocked_worlds"), dest);
    }

    private static boolean listContains(List<String> worlds, String worldName) {
        if (worlds == null || worlds.isEmpty() || worldName == null) {
            return false;
        }
        String target = worldName.toLowerCase(Locale.ROOT);
        for (String entry : worlds) {
            if (entry != null && entry.toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private void sendBlocked(Player player, Location to) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        String worldName = (to != null && to.getWorld() != null) ? to.getWorld().getName() : "?";
        placeholders.put("world", worldName);
        messageService.sendMessage(player, "dimension_blocked_in_combat", placeholders);
    }
}
