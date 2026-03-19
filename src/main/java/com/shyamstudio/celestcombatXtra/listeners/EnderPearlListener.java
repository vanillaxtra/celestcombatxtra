package com.shyamstudio.celestcombatXtra.listeners;

import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.language.ColorUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class EnderPearlListener implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    private ItemCooldownManager itemCooldownManager;

    // Track players with active pearl countdown displays to avoid duplicates
    private final Map<UUID, Scheduler.Task> pearlCountdownTasks = new ConcurrentHashMap<>();

    // Track thrown ender pearls to their player owners
    private final Map<Integer, UUID> activePearls = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();

        // Check if player is right-clicking with an ender pearl
        if (item != null && item.getType() == Material.ENDER_PEARL &&
                (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {

            // Check if ender pearls are completely blocked during combat
            if (plugin.getConfig().getBoolean("enderpearl.block_in_combat", false) && 
                    combatManager.isInCombat(player)) {
                event.setCancelled(true);
                plugin.getMessageService().sendMessage(player, "enderpearl_blocked_in_combat", new HashMap<>());
                return;
            }

            // Check if ender pearl is on cooldown - this now handles all conditions internally
            if (combatManager.isEnderPearlOnCooldown(player)) {
                event.setCancelled(true);

                // Send cooldown message
                int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
                // Keep the client-side cooldown overlay in sync.
                int remainingTicks = Math.max(0, remainingTime * 20);
                if (remainingTicks > 0) {
                    player.setCooldown(Material.ENDER_PEARL, remainingTicks);
                }
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("time", String.valueOf(remainingTime));
                plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl && event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();

            // Check if ender pearls are completely blocked during combat
            if (plugin.getConfig().getBoolean("enderpearl.block_in_combat", false) && 
                    combatManager.isInCombat(player)) {
                event.setCancelled(true);
                plugin.getMessageService().sendMessage(player, "enderpearl_blocked_in_combat", new HashMap<>());
                return;
            }

            // Check if ender pearl is on cooldown - this now handles all conditions internally
            if (combatManager.isEnderPearlOnCooldown(player)) {
                event.setCancelled(true);

                // Send cooldown message
                int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
                int remainingTicks = Math.max(0, remainingTime * 20);
                if (remainingTicks > 0) {
                    player.setCooldown(Material.ENDER_PEARL, remainingTicks);
                }
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("time", String.valueOf(remainingTime));
                plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
            } else {
                // Set cooldown when player successfully launches an ender pearl
                // The setEnderPearlCooldown method now handles all condition checks internally
                combatManager.setEnderPearlCooldown(player);

                // Start displaying the countdown for pearl cooldown
                startPearlCountdown(player);

                // Track this pearl to the player for the hit event
                activePearls.put(event.getEntity().getEntityId(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            // Get the pearl's entity ID
            int pearlId = event.getEntity().getEntityId();

            // Check if we're tracking this pearl
            if (activePearls.containsKey(pearlId)) {
                UUID playerUUID = activePearls.remove(pearlId);
                Player player = plugin.getServer().getPlayer(playerUUID);

                if (player != null && player.isOnline()) {
                    // Pearl landed, refresh combat if enabled
                    combatManager.refreshCombatOnPearlLand(player);
                }
            }
        }
    }

    /**
     * Starts a separate countdown task for pearl cooldown display.
     * This ensures the countdown is shown regardless of combat status.
     */
    private void startPearlCountdown(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        // Cancel any existing countdown task for this player
        Scheduler.Task existingTask = pearlCountdownTasks.get(playerUUID);
        if (existingTask != null) {
            existingTask.cancel();
        }

        // How often to update the countdown message (in ticks, 20 = 1 second)
        long updateInterval = 20L;

        // Create a new countdown task
        Scheduler.Task task = Scheduler.runTaskTimer(() -> {
            // Check if player is still online
            if (!player.isOnline()) {
                cancelPearlCountdown(playerUUID);
                return;
            }

            // Check if cooldown is still active
            if (!combatManager.isEnderPearlOnCooldown(player)) {
                cancelPearlCountdown(playerUUID);
                return;
            }

            // Get remaining time
            int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);

            // Send the appropriate message
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("time", String.valueOf(remainingTime));

            // If player is in combat, CombatManager will handle the combined message
            // Otherwise, send a pearl-specific message
            if (!combatManager.isInCombat(player)) {
                sendPhase1MergedActionBar(player, "pearl_only_countdown", placeholders);
            }

        }, 0L, updateInterval);

        // Store the task
        pearlCountdownTasks.put(playerUUID, task);
    }

    public void setItemCooldownManager(ItemCooldownManager manager) {
        this.itemCooldownManager = manager;
    }

    private void sendPhase1MergedActionBar(Player player, String baseActionBarKey, Map<String, String> basePlaceholders) {
        String baseActionBar = plugin.getLanguageManager().getActionBar(baseActionBarKey, basePlaceholders);
        if (baseActionBar == null) {
            plugin.getMessageService().sendMessage(player, baseActionBarKey, basePlaceholders);
            return;
        }

        StringBuilder merged = new StringBuilder(baseActionBar);
        if (itemCooldownManager != null) {
            itemCooldownManager.appendMergedCooldownSuffix(merged, player, true);
        }

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(ColorUtil.translateHexColorCodes(merged.toString())));
    }

    /**
     * Cancels and removes the pearl countdown task for a player.
     */
    private void cancelPearlCountdown(UUID playerUUID) {
        Scheduler.Task task = pearlCountdownTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Cleanup method to cancel all tasks when the plugin is disabled.
     * Call this from your main plugin's onDisable method.
     */
    public void shutdown() {
        pearlCountdownTasks.values().forEach(Scheduler.Task::cancel);
        pearlCountdownTasks.clear();
        activePearls.clear();
    }
}