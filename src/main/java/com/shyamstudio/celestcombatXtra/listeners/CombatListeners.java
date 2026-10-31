package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.api.CelestCombatAPI;
import com.shyamstudio.celestcombatXtra.api.events.PreCombatEvent;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.combat.DeathAnimationManager;
import com.shyamstudio.celestcombatXtra.language.MessageService;
import com.shyamstudio.celestcombatXtra.protection.NewbieProtectionManager;
import com.shyamstudio.celestcombatXtra.rewards.KillRewardManager;

import org.bukkit.World;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class CombatListeners implements Listener {
    private static final long EXPLOSION_TAG_MS = 3500L;
    private static final double EXPLOSION_TAG_RADIUS = 20.0;
    private static final long CRYSTAL_HIT_TTL_MS = TimeUnit.SECONDS.toMillis(30);

    private enum ExplosionKind {
        END_CRYSTAL("end_crystal"),
        RESPAWN_ANCHOR("respawn_anchor"),
        TNT_MINECART("tnt_minecart");

        private final String configKey;

        ExplosionKind(String configKey) {
            this.configKey = configKey;
        }
    }

    private record ExplosionTag(Location center, long timeMs, ExplosionKind kind) {}

    private final CelestCombatPro plugin;
    private CombatManager combatManager;
    private NewbieProtectionManager newbieProtectionManager;
    private KillRewardManager killRewardManager;
    private DeathAnimationManager deathAnimationManager;
    private MessageService messageService;

    private final Map<UUID, Boolean> playerLoggedOutInCombat = new ConcurrentHashMap<>();
    // Add a map to track the last damage source for each player
    private final Map<UUID, UUID> lastDamageSource = new ConcurrentHashMap<>();
    // Add a map to cleanup stale damage records
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> crystalLastHit = new ConcurrentHashMap<>();
    private final Map<UUID, Long> crystalHitTime = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ExplosionTag> recentExplosions = new CopyOnWriteArrayList<>();
    // Cleanup threshold (5 minutes)
    private static final long DAMAGE_RECORD_CLEANUP_THRESHOLD = TimeUnit.MINUTES.toMillis(5);

    public CombatListeners(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.combatManager = plugin.getCombatManager();
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageService = plugin.getMessageService();
    }

    /**
     * Reload all manager references to apply configuration changes
     */
    public void reload() {
        this.combatManager = plugin.getCombatManager();
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageService = plugin.getMessageService();

        plugin.debug("CombatListeners managers reloaded successfully");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCrystalHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }

        Player hitter = resolvePlayerDamager(event.getDamager());
        if (hitter == null) {
            return;
        }

        crystalLastHit.put(crystal.getUniqueId(), hitter.getUniqueId());
        crystalHitTime.put(crystal.getUniqueId(), System.currentTimeMillis());
        cleanupStaleCrystalHits();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!isExplosionTaggingEnabled()) {
            return;
        }

        Material type = event.getExplodedBlockState().getType();
        if (type == Material.RESPAWN_ANCHOR && isExplosionTypeEnabled(ExplosionKind.RESPAWN_ANCHOR)) {
            registerExplosionTag(event.getBlock().getLocation().add(0.5, 0.5, 0.5), ExplosionKind.RESPAWN_ANCHOR);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isExplosionTaggingEnabled()) {
            return;
        }

        Entity entity = event.getEntity();
        if (entity instanceof EnderCrystal && isExplosionTypeEnabled(ExplosionKind.END_CRYSTAL)) {
            registerExplosionTag(entity.getLocation(), ExplosionKind.END_CRYSTAL);
        } else if (entity.getType() == EntityType.TNT_MINECART
                && isExplosionTypeEnabled(ExplosionKind.TNT_MINECART)) {
            registerExplosionTag(entity.getLocation(), ExplosionKind.TNT_MINECART);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionCombatTag(EntityDamageEvent event) {
        if (!isExplosionTaggingEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }

        ExplosionKind kind = classifyExplosion(event, victim);
        if (kind == null || !isExplosionTypeEnabled(kind)) {
            return;
        }

        Player attacker = resolveExplosionAttacker(event, kind);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (attacker.hasPermission("celestcombatxtra.bypass.tag")
                || victim.hasPermission("celestcombatxtra.bypass.tag")) {
            return;
        }

        if (newbieProtectionManager.shouldProtectFromPvP()
                && newbieProtectionManager.hasProtection(victim)) {
            boolean shouldBlock = newbieProtectionManager.handleDamageReceived(victim, attacker);
            if (shouldBlock) {
                event.setCancelled(true);
                plugin.debug("Blocked explosion PvP damage to protected newbie: " + victim.getName());
                return;
            }
        }

        if (newbieProtectionManager.hasProtection(attacker)) {
            newbieProtectionManager.handleDamageDealt(attacker);
        }

        lastDamageSource.put(victim.getUniqueId(), attacker.getUniqueId());
        lastDamageTime.put(victim.getUniqueId(), System.currentTimeMillis());

        CelestCombatAPI.getCombatAPI().tagPlayer(attacker, victim, PreCombatEvent.CombatCause.EXPLOSION);
        CelestCombatAPI.getCombatAPI().tagPlayer(victim, attacker, PreCombatEvent.CombatCause.EXPLOSION);
        cleanupStaleDamageRecords();
    }

    private boolean isExplosionTaggingEnabled() {
        return plugin.getConfig().getBoolean("combat.tag_explosion_damage.enabled", true);
    }

    private boolean isExplosionTypeEnabled(ExplosionKind kind) {
        return isExplosionTaggingEnabled()
                && plugin.getConfig().getBoolean("combat.tag_explosion_damage." + kind.configKey, true);
    }

    private void registerExplosionTag(Location center, ExplosionKind kind) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        recentExplosions.removeIf(tag -> now - tag.timeMs > EXPLOSION_TAG_MS);
        recentExplosions.add(new ExplosionTag(center.clone(), now, kind));
    }

    private boolean isNearExplosionTag(Player player, ExplosionKind kind) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        long now = System.currentTimeMillis();
        World world = player.getWorld();
        Location playerLoc = player.getLocation();
        double radiusSquared = EXPLOSION_TAG_RADIUS * EXPLOSION_TAG_RADIUS;

        for (ExplosionTag tag : recentExplosions) {
            if (tag.kind != kind) {
                continue;
            }
            if (now - tag.timeMs > EXPLOSION_TAG_MS) {
                continue;
            }
            Location center = tag.center;
            if (center.getWorld() == null || !center.getWorld().equals(world)) {
                continue;
            }
            if (playerLoc.distanceSquared(center) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private ExplosionKind classifyExplosion(EntityDamageEvent event, Player victim) {
        DamageSource source = event.getDamageSource();
        Entity direct = source != null ? source.getDirectEntity() : null;

        if (direct instanceof EnderCrystal) {
            return ExplosionKind.END_CRYSTAL;
        }
        if (direct != null && direct.getType() == EntityType.TNT_MINECART) {
            return ExplosionKind.TNT_MINECART;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && source != null) {
            Location loc = source.getDamageLocation();
            if (loc != null && loc.getWorld() != null
                    && loc.getBlock().getType() == Material.RESPAWN_ANCHOR) {
                return ExplosionKind.RESPAWN_ANCHOR;
            }
        }

        if (isNearExplosionTag(victim, ExplosionKind.END_CRYSTAL)) {
            return ExplosionKind.END_CRYSTAL;
        }
        if (isNearExplosionTag(victim, ExplosionKind.TNT_MINECART)) {
            return ExplosionKind.TNT_MINECART;
        }
        if (isNearExplosionTag(victim, ExplosionKind.RESPAWN_ANCHOR)) {
            return ExplosionKind.RESPAWN_ANCHOR;
        }

        return null;
    }

    private Player resolveExplosionAttacker(EntityDamageEvent event, ExplosionKind kind) {
        DamageSource source = event.getDamageSource();
        if (source != null) {
            Entity causing = source.getCausingEntity();
            Player player = asOnlinePlayer(causing);
            if (player != null) {
                return player;
            }
        }

        if (kind == ExplosionKind.END_CRYSTAL && source != null) {
            Entity direct = source.getDirectEntity();
            if (direct instanceof EnderCrystal crystal) {
                UUID hitterId = crystalLastHit.get(crystal.getUniqueId());
                return asOnlinePlayer(hitterId);
            }
        }

        return null;
    }

    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private Player asOnlinePlayer(Entity entity) {
        if (entity instanceof Player player && player.isOnline()) {
            return player;
        }
        return null;
    }

    private Player asOnlinePlayer(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        return player != null && player.isOnline() ? player : null;
    }

    private void cleanupStaleCrystalHits() {
        long now = System.currentTimeMillis();
        crystalHitTime.entrySet().removeIf(entry -> now - entry.getValue() > CRYSTAL_HIT_TTL_MS);
        crystalLastHit.keySet().removeIf(uuid -> !crystalHitTime.containsKey(uuid));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = null;
        Player victim = null;

        if (event.getEntity() instanceof Player) {
            victim = (Player) event.getEntity();
        } else {
            return;
        }

        Entity damager = event.getDamager();

        if (damager instanceof Player) {
            attacker = (Player) damager;
        }
        else if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        // Handle newbie protection checks
        // Check if victim has newbie protection from PvP
        if (attacker != null && newbieProtectionManager.shouldProtectFromPvP() &&
                newbieProtectionManager.hasProtection(victim)) {

            // Handle the protection (sends messages and potentially removes protection)
            boolean shouldBlock = newbieProtectionManager.handleDamageReceived(victim, attacker);
            if (shouldBlock) {
                event.setCancelled(true);
                plugin.debug("Blocked PvP damage to protected newbie: " + victim.getName());
                return;
            }
        }

        // Check if victim has newbie protection from mobs (when attacker is null or not a player)
        else if (attacker == null && newbieProtectionManager.shouldProtectFromMobs() &&
                newbieProtectionManager.hasProtection(victim)) {
            event.setCancelled(true);
            plugin.debug("Blocked mob damage to protected newbie: " + victim.getName());
            return;
        }

        // Handle when protected player deals damage (removes protection if configured)
        if (attacker != null && newbieProtectionManager.hasProtection(attacker)) {
            newbieProtectionManager.handleDamageDealt(attacker);
        }

        // Continue with normal combat logic if damage wasn't blocked
        if (attacker != null && victim != null && !attacker.equals(victim)) {
            // Track this as the most recent damage source
            lastDamageSource.put(victim.getUniqueId(), attacker.getUniqueId());
            lastDamageTime.put(victim.getUniqueId(), System.currentTimeMillis());

            // Determine combat cause
            PreCombatEvent.CombatCause cause = PreCombatEvent.CombatCause.PLAYER_ATTACK;
            if (damager instanceof Projectile) {
                cause = PreCombatEvent.CombatCause.PROJECTILE;
            }

            // Combat tag both players using API
            CelestCombatAPI.getCombatAPI().tagPlayer(attacker, victim, cause);
            CelestCombatAPI.getCombatAPI().tagPlayer(victim, attacker, cause);

            // Perform cleanup of stale records
            cleanupStaleDamageRecords();
        }
    }

    private void cleanupStaleDamageRecords() {
        long currentTime = System.currentTimeMillis();
        lastDamageTime.entrySet().removeIf(entry ->
                (currentTime - entry.getValue()) > DAMAGE_RECORD_CLEANUP_THRESHOLD);

        // Also clean up damage sources for players that don't have a timestamp anymore
        lastDamageSource.keySet().removeIf(uuid -> !lastDamageTime.containsKey(uuid));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle newbie protection cleanup
        newbieProtectionManager.handlePlayerQuit(player);

        if (CelestCombatAPI.getCombatAPI().isInCombat(player)) {
            playerLoggedOutInCombat.put(player.getUniqueId(), true);

            // end combat for opponents who have no other online fighters left
            CelestCombatAPI.getCombatAPI().handlePlayerCombatExit(player);

            // Punish the player for combat logging using API
            CelestCombatAPI.getCombatAPI().punishCombatLogout(player);

        } else {
            playerLoggedOutInCombat.put(player.getUniqueId(), false);
        }
    }

    // Add a listener for PlayerKickEvent to track admin kicks
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();

        // Handle newbie protection cleanup
        newbieProtectionManager.handlePlayerQuit(player);

        if (CelestCombatAPI.getCombatAPI().isInCombat(player)) {
            // Check if exempt_admin_kick is enabled and this was an admin kick
            if (plugin.getConfig().getBoolean("combat.exempt_admin_kick", true)) {
                CelestCombatAPI.getCombatAPI().handlePlayerCombatExit(player);
                CelestCombatAPI.getCombatAPI().removeFromCombatSilently(player);
            } else {
                // Regular kick, treat as combat logout
                Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(player);
                playerLoggedOutInCombat.put(player.getUniqueId(), true);

                CelestCombatAPI.getCombatAPI().handlePlayerCombatExit(player);
                CelestCombatAPI.getCombatAPI().punishCombatLogout(player);

                if (opponent != null && opponent.isOnline()) {
                    killRewardManager.giveKillReward(opponent, player);
                    deathAnimationManager.performDeathAnimation(player, opponent);
                } else {
                    deathAnimationManager.performDeathAnimation(player, null);
                }

                CelestCombatAPI.getCombatAPI().removeFromCombatSilently(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        UUID victimId = victim.getUniqueId();

        // Remove newbie protection on death (if they had it)
        if (newbieProtectionManager.hasProtection(victim)) {
            newbieProtectionManager.removeProtection(victim, false);
            plugin.debug("Removed newbie protection from " + victim.getName() + " due to death");
        }

        // If player directly killed by another player
        if (killer != null && !killer.equals(victim)) {
            // Execute kill reward commands using KillRewardManager
            killRewardManager.giveKillReward(killer, victim);

            // Perform death animation
            deathAnimationManager.performDeathAnimation(victim, killer);

            // end combat for opponents who only had this victim left
            CelestCombatAPI.getCombatAPI().handlePlayerCombatExit(victim);

            // Always remove victim from combat
            CelestCombatAPI.getCombatAPI().removeFromCombat(victim);

            // Killer can only be put out of combat if they have full diamond armor
            if (hasFullDiamondArmor(killer)) {
                CelestCombatAPI.getCombatAPI().removeFromCombat(killer);
            }
        }
        // If player died by other causes but was in combat
        else if (CelestCombatAPI.getCombatAPI().isInCombat(victim)) {
            Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(victim);

            // Check if we have an opponent or a recent damage source
            if (opponent != null && opponent.isOnline()) {
                // Give rewards to the combat opponent
                killRewardManager.giveKillReward(opponent, victim);
                deathAnimationManager.performDeathAnimation(victim, opponent);
            } else if (lastDamageSource.containsKey(victimId)) {
                // Try to get the last player who damaged this player
                UUID lastAttackerUuid = lastDamageSource.get(victimId);
                Player lastAttacker = plugin.getServer().getPlayer(lastAttackerUuid);

                if (lastAttacker != null && lastAttacker.isOnline() && !lastAttacker.equals(victim)) {
                    killRewardManager.giveKillReward(lastAttacker, victim);
                    deathAnimationManager.performDeathAnimation(victim, lastAttacker);
                } else {
                    // No valid attacker found
                    deathAnimationManager.performDeathAnimation(victim, null);
                }
            } else {
                // No attacker information available
                deathAnimationManager.performDeathAnimation(victim, null);
            }

            CelestCombatAPI.getCombatAPI().handlePlayerCombatExit(victim);
            CelestCombatAPI.getCombatAPI().removeFromCombat(victim);

            // Clean up damage tracking
            lastDamageSource.remove(victimId);
            lastDamageTime.remove(victimId);
        } else {
            // Player died outside of combat
            deathAnimationManager.performDeathAnimation(victim, null);

            // Clean up any stale damage tracking
            lastDamageSource.remove(victimId);
            lastDamageTime.remove(victimId);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Handle newbie protection for new players
        newbieProtectionManager.handlePlayerJoin(player);

        if (playerLoggedOutInCombat.containsKey(playerUUID)) {
            if (playerLoggedOutInCombat.get(playerUUID)) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                messageService.sendMessage(player, "player_died_combat_logout", placeholders);
            }
            // Clean up the map to prevent memory leaks
            playerLoggedOutInCombat.remove(playerUUID);
        }

        // Clean up any stale damage records for this player
        lastDamageSource.remove(playerUUID);
        lastDamageTime.remove(playerUUID);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (CelestCombatAPI.getCombatAPI().isInCombat(player)) {
            String command = event.getMessage().split(" ")[0].toLowerCase().substring(1);

            // Get command blocking mode from config
            String blockMode = plugin.getConfig().getString("combat.command_block_mode", "whitelist").toLowerCase();

            // Determine if the command should be blocked based on the mode
            boolean shouldBlock = false;

            if ("blacklist".equalsIgnoreCase(blockMode)) {
                // Blacklist mode - block commands in the list
                List<String> blockedCommands = plugin.getConfig().getStringList("combat.blocked_commands");

                for (String blockedCmd : blockedCommands) {
                    if (command.equalsIgnoreCase(blockedCmd) ||
                            (blockedCmd.endsWith("*") && command.startsWith(blockedCmd.substring(0, blockedCmd.length() - 1)))) {
                        shouldBlock = true;
                        break;
                    }
                }
            } else {
                // Whitelist mode - allow only commands in the list
                List<String> allowedCommands = plugin.getConfig().getStringList("combat.allowed_commands");
                shouldBlock = true; // Block by default

                for (String allowedCmd : allowedCommands) {
                    if (command.equalsIgnoreCase(allowedCmd) ||
                            (allowedCmd.endsWith("*") && command.startsWith(allowedCmd.substring(0, allowedCmd.length() - 1)))) {
                        shouldBlock = false; // Command is allowed
                        break;
                    }
                }
            }

            // Block the command if necessary
            if (shouldBlock) {
                event.setCancelled(true);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("command", command);
                placeholders.put("time", String.valueOf(CelestCombatAPI.getCombatAPI().getRemainingCombatTime(player)));
                messageService.sendMessage(player, "command_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // If player is trying to enable flight
        if (event.isFlying() && CelestCombatAPI.getCombatAPI().shouldDisableFlight(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Checks if the player has full diamond armor (helmet, chestplate, leggings, boots).
     * Only players with full diamond armor can be put out of combat when they get a kill.
     */
    private boolean hasFullDiamondArmor(Player player) {
        if (player == null) return false;

        return isDiamondPiece(player.getInventory().getHelmet()) &&
                isDiamondPiece(player.getInventory().getChestplate()) &&
                isDiamondPiece(player.getInventory().getLeggings()) &&
                isDiamondPiece(player.getInventory().getBoots());
    }

    private boolean isDiamondPiece(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;

        return switch (item.getType()) {
            case DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS -> true;
            default -> false;
        };
    }

    // Method to clean up any lingering data when the plugin disables
    public void shutdown() {
        playerLoggedOutInCombat.clear();
        lastDamageSource.clear();
        lastDamageTime.clear();
        crystalLastHit.clear();
        crystalHitTime.clear();
        recentExplosions.clear();
    }
}