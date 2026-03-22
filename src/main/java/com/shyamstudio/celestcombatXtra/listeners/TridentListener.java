package com.shyamstudio.celestcombatXtra.listeners;

import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
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
public class TridentListener implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    private ItemCooldownManager itemCooldownManager;

    // Track players with active trident countdown displays to avoid duplicates
    private final Map<UUID, Scheduler.Task> tridentCountdownTasks = new ConcurrentHashMap<>();

    // Track thrown tridents to their player owners
    private final Map<Integer, UUID> activeTridents = new ConcurrentHashMap<>();

    // Store original locations for riptide rollback
    private final Map<UUID, Location> riptideOriginalLocations = new ConcurrentHashMap<>();

    /**
     * {@link PlayerInteractEvent#getItem()} is often {@code null} on {@link Action#RIGHT_CLICK_AIR} even when the
     * player is clearly using a trident — only block clicks reliably populate it. Resolve from the interacting hand.
     */
    private static ItemStack resolveTridentUsed(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack fromEvent = event.getItem();
        if (fromEvent != null && fromEvent.getType() == Material.TRIDENT) {
            return fromEvent;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == EquipmentSlot.OFF_HAND) {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (off != null && off.getType() == Material.TRIDENT) {
                return off;
            }
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() == Material.TRIDENT) {
            return main;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && off.getType() == Material.TRIDENT) {
            return off;
        }
        return null;
    }

    private static void denyTridentInteract(PlayerInteractEvent event, Action action) {
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTridentUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = resolveTridentUsed(event);
        if (item == null || item.getType() != Material.TRIDENT) {
            return;
        }

        // Check if trident usage is banned in this world
        if (combatManager.isTridentBanned(player)) {
            denyTridentInteract(event, action);
            sendBannedMessage(player);
            return;
        }

        // Check if tridents are blocked during combat
        if (combatManager.isInCombat(player) && isTridentBlockedInCombat()) {
            denyTridentInteract(event, action);
            sendCombatBlockedMessage(player);
            return;
        }

        // Handle riptide tridents differently - we need to prevent the interaction entirely
        if (item.containsEnchantment(Enchantment.RIPTIDE)) {
            if (combatManager.isTridentOnCooldown(player)) {
                denyTridentInteract(event, action);
                syncTridentClientCooldown(player);
                sendCooldownMessage(player);
                return;
            }
            // Store the player's location before riptide for potential rollback
            riptideOriginalLocations.put(player.getUniqueId(), player.getLocation().clone());
        } else {
            // Handle non-riptide tridents
            if (combatManager.isTridentOnCooldown(player)) {
                denyTridentInteract(event, action);
                syncTridentClientCooldown(player);
                sendCooldownMessage(player);
            }
        }

        // When allowed to use trident and right-clicking a block, deny block interaction so trident can charge
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiptideUse(PlayerRiptideEvent event) {
        Player player = event.getPlayer();

        // Check if trident usage is banned in this world
        if (combatManager.isTridentBanned(player)) {
            sendBannedMessage(player);
            rollbackRiptide(player);
            return;
        }

        // Check if tridents are blocked during combat
        if (combatManager.isInCombat(player) && isTridentBlockedInCombat()) {
            sendCombatBlockedMessage(player);
            rollbackRiptide(player);
            return;
        }

        // Check if trident is on cooldown
        if (combatManager.isTridentOnCooldown(player)) {
            syncTridentClientCooldown(player);
            sendCooldownMessage(player);
            rollbackRiptide(player);
            return;
        }

        // Set cooldown for riptide usage
        combatManager.setTridentCooldown(player);

        // Start displaying the countdown
        startTridentCountdown(player);

        // Refresh combat on riptide usage if enabled
        combatManager.refreshCombatOnTridentLand(player);

        // Clean up the stored location
        riptideOriginalLocations.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerLaunchProjectile(PlayerLaunchProjectileEvent event) {
        if (!(event.getProjectile() instanceof Trident)) return;
        Player player = event.getPlayer();
        if (!combatManager.isInCombat(player) || !isTridentBlockedInCombat()) return;
        event.setCancelled(true);
        sendCombatBlockedMessage(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Trident && event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();

            // Check if trident usage is banned in this world
            if (combatManager.isTridentBanned(player)) {
                event.setCancelled(true);
                event.getEntity().remove();
                sendBannedMessage(player);
                return;
            }

            // Check if tridents are blocked during combat
            if (combatManager.isInCombat(player) && isTridentBlockedInCombat()) {
                event.setCancelled(true);
                event.getEntity().remove();
                sendCombatBlockedMessage(player);
                return;
            }

            // Check if trident is on cooldown
            if (combatManager.isTridentOnCooldown(player)) {
                event.setCancelled(true);
                event.getEntity().remove();
                syncTridentClientCooldown(player);
                sendCooldownMessage(player);
            } else {
                // Set cooldown when player successfully launches a trident (non-riptide)
                combatManager.setTridentCooldown(player);

                // Start displaying the countdown for trident cooldown
                startTridentCountdown(player);

                // Track this trident to the player for the hit event
                activeTridents.put(event.getEntity().getEntityId(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Trident) {
            // Get the trident's entity ID
            int tridentId = event.getEntity().getEntityId();

            // Check if we're tracking this trident
            if (activeTridents.containsKey(tridentId)) {
                UUID playerUUID = activeTridents.remove(tridentId);
                Player player = plugin.getServer().getPlayer(playerUUID);

                if (player != null && player.isOnline()) {
                    // Trident landed, refresh combat if enabled
                    combatManager.refreshCombatOnTridentLand(player);
                }
            }
        }
    }

    /** Syncs the physical item cooldown overlay when blocking use (e.g. after relog). */
    private void syncTridentClientCooldown(Player player) {
        if (player == null) return;
        int remaining = combatManager.getRemainingTridentCooldown(player);
        if (remaining > 0) {
            player.setCooldown(Material.TRIDENT, Math.max(1, remaining * 20));
        }
    }

    private void rollbackRiptide(Player player) {
        Location originalLocation = riptideOriginalLocations.remove(player.getUniqueId());

        if (originalLocation != null) {
            // Method 2: Alternative approach - counter the velocity after a short delay
            Scheduler.runTaskLater(() -> {
                if (player.isOnline()) {
                    // Stop any remaining velocity
                    player.setVelocity(player.getVelocity().multiply(0));

                    // Ensure they're at the original location
                    if (player.getLocation().distance(originalLocation) > 5) {
                        player.teleport(originalLocation);
                    }
                }
            }, 2L);
        } else {
            // Fallback: just stop their velocity and add effects
            Scheduler.runTask(() -> {
                player.setVelocity(player.getVelocity().multiply(0));
            });
        }
    }

    /**
     * Starts a separate countdown task for trident cooldown display.
     * This ensures the countdown is shown regardless of combat status.
     */
    private void startTridentCountdown(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        // Cancel any existing countdown task for this player
        Scheduler.Task existingTask = tridentCountdownTasks.get(playerUUID);
        if (existingTask != null) {
            existingTask.cancel();
        }

        // How often to update the countdown message (in ticks, 20 = 1 second)
        long updateInterval = 20L;

        // Create a new countdown task
        Scheduler.Task task = Scheduler.runTaskTimer(() -> {
            // Check if player is still online
            if (!player.isOnline()) {
                cancelTridentCountdown(playerUUID);
                return;
            }

            // Check if cooldown is still active
            if (!combatManager.isTridentOnCooldown(player)) {
                cancelTridentCountdown(playerUUID);
                return;
            }

            // Get remaining time
            int remainingTime = combatManager.getRemainingTridentCooldown(player);

            // Send the appropriate message
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("time", String.valueOf(remainingTime));

            // If player is in combat, CombatManager will handle the combined message
            // Otherwise, send a trident-specific message
            if (!combatManager.isInCombat(player)) {
                sendPhase1MergedActionBar(player, "trident_only_countdown", placeholders);
            }

        }, 0L, updateInterval);

        // Store the task
        tridentCountdownTasks.put(playerUUID, task);
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
     * Cancels and removes the trident countdown task for a player.
     */
    private void cancelTridentCountdown(UUID playerUUID) {
        Scheduler.Task task = tridentCountdownTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Helper method to send banned message
     */
    private void sendBannedMessage(Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        plugin.getMessageService().sendMessage(player, "trident_banned", placeholders);
    }

    /**
     * Helper method to send cooldown message
     */
    private void sendCooldownMessage(Player player) {
        int remainingTime = combatManager.getRemainingTridentCooldown(player);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(remainingTime));
        plugin.getMessageService().sendMessage(player, "trident_cooldown", placeholders);
    }

    /**
     * Helper method to send combat blocked message
     */
    private void sendCombatBlockedMessage(Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        plugin.getMessageService().sendMessage(player, "trident_blocked_in_combat", placeholders);
    }

    /**
     * Check if tridents are blocked during combat
     */
    private boolean isTridentBlockedInCombat() {
        return plugin.getConfig().getBoolean("trident.block_in_combat", true);
    }

    /**
     * Cleanup method to cancel all tasks when the plugin is disabled.
     * Call this from your main plugin's onDisable method.
     */
    public void shutdown() {
        tridentCountdownTasks.values().forEach(Scheduler.Task::cancel);
        tridentCountdownTasks.clear();
        activeTridents.clear();
        riptideOriginalLocations.clear();
    }
}