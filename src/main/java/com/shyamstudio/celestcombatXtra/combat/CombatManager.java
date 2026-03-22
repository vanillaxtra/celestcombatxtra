package com.shyamstudio.celestcombatXtra.combat;

import lombok.Getter;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.configs.WindchargeConfigPaths;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.language.ColorUtil;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatManager {
    private final CelestCombatPro plugin;
    @Getter private final Map<UUID, Long> playersInCombat;
    private final Map<UUID, Scheduler.Task> combatTasks;
    private final Map<UUID, UUID> combatOpponents;

    // Single countdown task instead of per-player tasks
    private Scheduler.Task globalCountdownTask;
    private static final long COUNTDOWN_INTERVAL = 20L; // 1 second in ticks

    @Getter private final Map<UUID, Long> enderPearlCooldowns;
    @Getter private final Map<UUID, Long> tridentCooldowns = new ConcurrentHashMap<>();

    // Combat configuration cache to avoid repeated config lookups
    private long combatDurationTicks;
    private long combatDurationSeconds;
    private boolean disableFlightInCombat;
    private long enderPearlCooldownTicks;
    private long enderPearlCooldownSeconds;
    private Map<String, Boolean> worldEnderPearlSettings = new ConcurrentHashMap<>();
    private boolean enderPearlInCombatOnly;
    private boolean enderPearlEnabled;
    private boolean refreshCombatOnPearlLand;

    // Trident configuration cache
    private long tridentCooldownTicks;
    private long tridentCooldownSeconds;
    private Map<String, Boolean> worldTridentSettings = new ConcurrentHashMap<>();
    private boolean tridentInCombatOnly;
    private boolean tridentEnabled;
    private boolean refreshCombatOnTridentLand;
    private Map<String, Boolean> worldTridentBannedSettings = new ConcurrentHashMap<>();
    private boolean refreshCombatOnWindChargeThrow;

    // Cleanup task for expired cooldowns
    private Scheduler.Task cleanupTask;
    private static final long CLEANUP_INTERVAL = 12000L; // 10 minutes in ticks

    private ItemCooldownManager itemCooldownManager;
    private final CombatNametagManager combatNametagManager;
    private final Map<UUID, BossBar> combatBossBars = new ConcurrentHashMap<>();
    private boolean bossBarEnabled;
    private String bossBarTitleTemplate;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;

    public CombatManager(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.playersInCombat = new ConcurrentHashMap<>();
        this.combatTasks = new ConcurrentHashMap<>();
        this.combatOpponents = new ConcurrentHashMap<>();
        this.enderPearlCooldowns = new ConcurrentHashMap<>();

        // Cache configuration values to avoid repeated lookups
        this.combatDurationTicks = plugin.getTimeFromConfig("combat.duration", "20s");
        this.combatDurationSeconds = combatDurationTicks / 20;
        this.disableFlightInCombat = plugin.getConfig().getBoolean("combat.disable_flight", true);

        this.enderPearlCooldownTicks = plugin.getTimeFromConfig(getEnderPearlCooldownPath("duration"), "10s");
        this.enderPearlCooldownSeconds = enderPearlCooldownTicks / 20;
        this.enderPearlEnabled = plugin.getConfig().getBoolean(getEnderPearlCooldownPath("enabled"), true);
        this.enderPearlInCombatOnly = plugin.getConfig().getBoolean(getEnderPearlCooldownPath("in_combat_only"), true);
        this.refreshCombatOnPearlLand = plugin.getConfig().getBoolean("enderpearl.refresh_combat_on_land", false);

        String tridentCooldownDurationPath = plugin.getConfig().contains("trident.cooldown.duration")
                ? "trident.cooldown.duration"
                : "trident_cooldown.duration";
        String tridentCooldownEnabledPath = plugin.getConfig().contains("trident.cooldown.enabled")
                ? "trident.cooldown.enabled"
                : "trident_cooldown.enabled";
        String tridentCooldownInCombatOnlyPath = plugin.getConfig().contains("trident.cooldown.in_combat_only")
                ? "trident.cooldown.in_combat_only"
                : "trident_cooldown.in_combat_only";

        this.tridentCooldownTicks = plugin.getTimeFromConfig(tridentCooldownDurationPath, "10s");
        this.tridentCooldownSeconds = tridentCooldownTicks / 20;
        this.tridentEnabled = plugin.getConfig().getBoolean(tridentCooldownEnabledPath, true);
        this.tridentInCombatOnly = plugin.getConfig().getBoolean(tridentCooldownInCombatOnlyPath, true);
        this.refreshCombatOnTridentLand = plugin.getConfig().getBoolean("trident.refresh_combat_on_land", false);
        this.refreshCombatOnWindChargeThrow = plugin.getConfig().getBoolean(
                WindchargeConfigPaths.root(plugin.getConfig()) + ".refresh_combat_on_throw", false);

        // Load per-world settings
        loadWorldTridentSettings();

        // Load per-world settings
        loadWorldEnderPearlSettings();

        // Start the global countdown timer
        startGlobalCountdownTimer();

        this.combatNametagManager = new CombatNametagManager(plugin, this);
        loadBossBarConfig();
    }

    private void loadBossBarConfig() {
        this.bossBarEnabled = plugin.getConfig().getBoolean("combat.bossbar.enabled", false);
        this.bossBarTitleTemplate = plugin.getConfig().getString("combat.bossbar.title", "&cCombat: &f%time%s remaining");
        String colorStr = plugin.getConfig().getString("combat.bossbar.color", "RED");
        try {
            this.bossBarColor = BarColor.valueOf(colorStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            this.bossBarColor = BarColor.RED;
        }
        String styleStr = plugin.getConfig().getString("combat.bossbar.style", "SOLID");
        try {
            this.bossBarStyle = BarStyle.valueOf(styleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            this.bossBarStyle = BarStyle.SOLID;
        }
    }

    public void setItemCooldownManager(ItemCooldownManager manager) {
        this.itemCooldownManager = manager;
    }

    private void loadWorldTridentSettings() {
        worldTridentSettings.clear();
        worldTridentBannedSettings.clear();

        // Load cooldown settings per world
        String tridentWorldsPath = plugin.getConfig().isConfigurationSection("trident.cooldown.worlds")
                ? "trident.cooldown.worlds"
                : "trident_cooldown.worlds";

        if (plugin.getConfig().isConfigurationSection(tridentWorldsPath)) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection(tridentWorldsPath)).getKeys(false)) {
                boolean enabled = plugin.getConfig().getBoolean(tridentWorldsPath + "." + worldName, true);
                worldTridentSettings.put(worldName, enabled);
            }
        }

        // Load banned settings per world
        if (plugin.getConfig().isConfigurationSection("trident.banned_worlds")) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection("trident.banned_worlds")).getKeys(false)) {
                boolean banned = plugin.getConfig().getBoolean("trident.banned_worlds." + worldName, false);
                worldTridentBannedSettings.put(worldName, banned);
            }
        }

        // plugin.getLogger().info("Loaded world-specific trident settings: " + worldTridentSettings);
    }

    public void reloadConfig() {
        // Update cached configuration values
        this.combatDurationTicks = plugin.getTimeFromConfig("combat.duration", "20s");
        this.combatDurationSeconds = combatDurationTicks / 20;
        this.disableFlightInCombat = plugin.getConfig().getBoolean("combat.disable_flight", true);

        this.enderPearlCooldownTicks = plugin.getTimeFromConfig(getEnderPearlCooldownPath("duration"), "10s");
        this.enderPearlCooldownSeconds = enderPearlCooldownTicks / 20;
        this.enderPearlEnabled = plugin.getConfig().getBoolean(getEnderPearlCooldownPath("enabled"), true);
        this.enderPearlInCombatOnly = plugin.getConfig().getBoolean(getEnderPearlCooldownPath("in_combat_only"), true);
        this.refreshCombatOnPearlLand = plugin.getConfig().getBoolean("enderpearl.refresh_combat_on_land", false);
        loadWorldEnderPearlSettings();

        String tridentCooldownDurationPath = plugin.getConfig().contains("trident.cooldown.duration")
                ? "trident.cooldown.duration"
                : "trident_cooldown.duration";
        String tridentCooldownEnabledPath = plugin.getConfig().contains("trident.cooldown.enabled")
                ? "trident.cooldown.enabled"
                : "trident_cooldown.enabled";
        String tridentCooldownInCombatOnlyPath = plugin.getConfig().contains("trident.cooldown.in_combat_only")
                ? "trident.cooldown.in_combat_only"
                : "trident_cooldown.in_combat_only";

        this.tridentCooldownTicks = plugin.getTimeFromConfig(tridentCooldownDurationPath, "10s");
        this.tridentCooldownSeconds = tridentCooldownTicks / 20;
        this.tridentEnabled = plugin.getConfig().getBoolean(tridentCooldownEnabledPath, true);
        this.tridentInCombatOnly = plugin.getConfig().getBoolean(tridentCooldownInCombatOnlyPath, true);
        this.refreshCombatOnTridentLand = plugin.getConfig().getBoolean("trident.refresh_combat_on_land", false);
        this.refreshCombatOnWindChargeThrow = plugin.getConfig().getBoolean(
                WindchargeConfigPaths.root(plugin.getConfig()) + ".refresh_combat_on_throw", false);
        loadWorldTridentSettings();
        loadBossBarConfig();
    }


    // Add this method to load world-specific settings
    private void loadWorldEnderPearlSettings() {
        worldEnderPearlSettings.clear();

        String worldsPath = getEnderPearlCooldownPath("worlds");
        if (plugin.getConfig().isConfigurationSection(worldsPath)) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection(worldsPath)).getKeys(false)) {
                boolean enabled = plugin.getConfig().getBoolean(worldsPath + "." + worldName, true);
                worldEnderPearlSettings.put(worldName, enabled);
            }
        }
    }

    private String getEnderPearlCooldownPath(String suffix) {
        String nestedPath = "enderpearl.cooldown." + suffix;
        if (plugin.getConfig().contains(nestedPath) || plugin.getConfig().isConfigurationSection(nestedPath)) {
            return nestedPath;
        }
        return "enderpearl_cooldown." + suffix;
    }

    private void startGlobalCountdownTimer() {
        if (globalCountdownTask != null) {
            globalCountdownTask.cancel();
        }

        globalCountdownTask = Scheduler.runTaskTimer(() -> {
            long currentTime = System.currentTimeMillis();

            // Process all players in a single timer tick
            Set<UUID> toUpdate = new HashSet<>();

            for (Map.Entry<UUID, Long> entry : new HashMap<>(playersInCombat).entrySet()) {
                UUID playerUUID = entry.getKey();
                long combatEndTime = entry.getValue();

                // Check if combat has expired
                if (currentTime > combatEndTime) {
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        removeFromCombat(player);
                    } else {
                        // Player is offline, clean up
                        playersInCombat.remove(playerUUID);
                        combatOpponents.remove(playerUUID);
                        Scheduler.Task task = combatTasks.remove(playerUUID);
                        if (task != null) {
                            task.cancel();
                        }
                    }
                    continue;
                }
                toUpdate.add(playerUUID);
            }

            // Include players with pearl or trident cooldown (even when not in combat)
            // so action bar shows countdown for full cooldown duration, same as wind charge
            for (Map.Entry<UUID, Long> entry : enderPearlCooldowns.entrySet()) {
                if (currentTime <= entry.getValue() && Bukkit.getPlayer(entry.getKey()) != null) {
                    toUpdate.add(entry.getKey());
                }
            }
            for (Map.Entry<UUID, Long> entry : tridentCooldowns.entrySet()) {
                if (currentTime <= entry.getValue() && Bukkit.getPlayer(entry.getKey()) != null) {
                    toUpdate.add(entry.getKey());
                }
            }

            for (UUID playerUUID : toUpdate) {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    updatePlayerCountdown(player, currentTime);
                }
            }

            // Handle ender pearl cooldowns
            enderPearlCooldowns.entrySet().removeIf(entry ->
                    currentTime > entry.getValue() ||
                            Bukkit.getPlayer(entry.getKey()) == null
            );

            tridentCooldowns.entrySet().removeIf(entry ->
                    currentTime > entry.getValue() ||
                            Bukkit.getPlayer(entry.getKey()) == null
            );

        }, 0L, COUNTDOWN_INTERVAL);
    }

    private void updatePlayerCountdown(Player player, long currentTime) {
        if (player == null || !player.isOnline()) return;

        UUID playerUUID = player.getUniqueId();
        boolean inCombat = playersInCombat.containsKey(playerUUID) &&
                currentTime <= playersInCombat.get(playerUUID);
        boolean hasPearlCooldown = enderPearlCooldowns.containsKey(playerUUID) &&
                currentTime <= enderPearlCooldowns.get(playerUUID);
        boolean hasTridentCooldown = tridentCooldowns.containsKey(playerUUID) &&
                currentTime <= tridentCooldowns.get(playerUUID);

        if (!inCombat && !hasPearlCooldown && !hasTridentCooldown) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());

        if (inCombat) {
            int remainingCombatTime = getRemainingCombatTime(player, currentTime);
            placeholders.put("combat_time", String.valueOf(remainingCombatTime));

            if (hasPearlCooldown && hasTridentCooldown) {
                // All three cooldowns active - show combined message
                int remainingPearlTime = getRemainingEnderPearlCooldown(player, currentTime);
                int remainingTridentTime = getRemainingTridentCooldown(player, currentTime);

                placeholders.put("pearl_time", String.valueOf(remainingPearlTime));
                placeholders.put("trident_time", String.valueOf(remainingTridentTime));
                sendPhase1MergedActionBar(player, "combat_pearl_trident_countdown", placeholders);
            } else if (hasPearlCooldown) {
                // Combat + pearl cooldown active
                int remainingPearlTime = getRemainingEnderPearlCooldown(player, currentTime);
                placeholders.put("pearl_time", String.valueOf(remainingPearlTime));
                sendPhase1MergedActionBar(player, "combat_pearl_countdown", placeholders);
            } else if (hasTridentCooldown) {
                // Combat + trident cooldown active
                int remainingTridentTime = getRemainingTridentCooldown(player, currentTime);
                placeholders.put("trident_time", String.valueOf(remainingTridentTime));
                sendPhase1MergedActionBar(player, "combat_trident_countdown", placeholders);
            } else {
                // Only combat cooldown active (optionally merged with wind like combat + pearl)
                if (remainingCombatTime > 0) {
                    boolean wind = itemCooldownManager != null
                            && itemCooldownManager.isWindChargeOnCooldown(player);
                    if (wind) {
                        placeholders.put("combat_time", String.valueOf(remainingCombatTime));
                        placeholders.put("wind_time", String.valueOf(
                                itemCooldownManager.getRemainingWindChargeCooldown(player)));
                        sendPhase1MergedActionBar(player, "combat_wind_countdown", placeholders, false);
                    } else {
                        placeholders.put("time", String.valueOf(remainingCombatTime));
                        sendPhase1MergedActionBar(player, "combat_countdown", placeholders);
                    }
                }
            }
        } else if (hasPearlCooldown && hasTridentCooldown) {
            // Both pearl and trident cooldowns but no combat
            int remainingPearlTime = getRemainingEnderPearlCooldown(player, currentTime);
            int remainingTridentTime = getRemainingTridentCooldown(player, currentTime);

            placeholders.put("pearl_time", String.valueOf(remainingPearlTime));
            placeholders.put("trident_time", String.valueOf(remainingTridentTime));
            sendPhase1MergedActionBar(player, "pearl_trident_countdown", placeholders);
        } else if (hasPearlCooldown) {
            // Only pearl cooldown active
            int remainingPearlTime = getRemainingEnderPearlCooldown(player, currentTime);
            if (remainingPearlTime > 0) {
                placeholders.put("time", String.valueOf(remainingPearlTime));
                sendPhase1MergedActionBar(player, "pearl_only_countdown", placeholders);
            }
        } else if (hasTridentCooldown) {
            // Only trident cooldown active
            int remainingTridentTime = getRemainingTridentCooldown(player, currentTime);
            if (remainingTridentTime > 0) {
                placeholders.put("time", String.valueOf(remainingTridentTime));
                sendPhase1MergedActionBar(player, "trident_only_countdown", placeholders);
            }
        }

        if (inCombat) {
            combatNametagManager.refresh(player);
            updateCombatBossBar(player, getRemainingCombatTime(player, currentTime));
        } else {
            clearCombatBossBar(player);
        }
    }

    private void updateCombatBossBar(Player player, int remainingSeconds) {
        if (!bossBarEnabled || player == null || !player.isOnline()) return;

        BossBar bar = combatBossBars.computeIfAbsent(player.getUniqueId(), u -> {
            BossBar b = Bukkit.createBossBar("", bossBarColor, bossBarStyle);
            b.addPlayer(player);
            return b;
        });

        String title = bossBarTitleTemplate.replace("%time%", String.valueOf(remainingSeconds));
        bar.setTitle(com.shyamstudio.celestcombatXtra.language.ColorUtil.translateHexColorCodes(
                org.bukkit.ChatColor.translateAlternateColorCodes('&', title)));
        bar.setProgress(Math.max(0, Math.min(1, remainingSeconds / (double) combatDurationSeconds)));
        bar.setVisible(true);
    }

    private void clearCombatBossBar(Player player) {
        if (player == null) return;
        BossBar bar = combatBossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void sendPhase1MergedActionBar(Player player, String baseActionBarKey, Map<String, String> basePlaceholders) {
        sendPhase1MergedActionBar(player, baseActionBarKey, basePlaceholders, true);
    }

    /**
     * @param appendWindCharge when false, wind is already part of {@code baseActionBarKey} (e.g. combat_wind_countdown).
     */
    private void sendPhase1MergedActionBar(Player player, String baseActionBarKey, Map<String, String> basePlaceholders,
            boolean appendWindCharge) {
        if (player == null || !player.isOnline()) return;

        String baseActionBar = plugin.getLanguageManager().getActionBar(baseActionBarKey, basePlaceholders);
        if (baseActionBar == null) return;

        StringBuilder merged = new StringBuilder(baseActionBar);

        if (itemCooldownManager != null) {
            itemCooldownManager.appendMergedCooldownSuffix(merged, player, appendWindCharge);
        }

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(ColorUtil.translateHexColorCodes(merged.toString())));
    }

    public void tagPlayer(Player player, Player attacker) {
        if (player == null || attacker == null) return;

        if (player.hasPermission("celestcombatxtra.bypass.tag")) {
            return;
        }

        UUID playerUUID = player.getUniqueId();
        long newEndTime = System.currentTimeMillis() + (combatDurationSeconds * 1000L);

        boolean alreadyInCombat = playersInCombat.containsKey(playerUUID);
        boolean alreadyInCombatWithAttacker = alreadyInCombat &&
                attacker.getUniqueId().equals(combatOpponents.get(playerUUID));

        if (alreadyInCombatWithAttacker) {
            long currentEndTime = playersInCombat.get(playerUUID);
            if (newEndTime <= currentEndTime) {
                return; // Don't reset the timer if it would make it shorter
            }
        }

        // Check if we should disable flight
        if (shouldDisableFlight(player) && player.isFlying()) {
            player.setFlying(false);
        }

        combatOpponents.put(playerUUID, attacker.getUniqueId());
        playersInCombat.put(playerUUID, newEndTime);

        // Cancel existing task if any
        Scheduler.Task existingTask = combatTasks.get(playerUUID);
        if (existingTask != null) {
            existingTask.cancel();
        }

        combatNametagManager.refresh(player);
        combatNametagManager.refresh(attacker);
    }

    public void punishCombatLogout(Player player) {
        if (player == null) return;

        player.setHealth(0);
        removeFromCombat(player);
    }

    public void removeFromCombat(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        if (!playersInCombat.containsKey(playerUUID)) {
            return; // Player is not in combat
        }

        combatNametagManager.clear(player);
        clearCombatBossBar(player);

        playersInCombat.remove(playerUUID);
        combatOpponents.remove(playerUUID);

        Scheduler.Task task = combatTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
        }

        // Send appropriate message if player was in combat
        if (player.isOnline()) {
            plugin.getMessageService().sendMessage(player, "combat_expired");
        }
    }

    public void removeFromCombatSilently(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        combatNametagManager.clear(player);
        clearCombatBossBar(player);

        playersInCombat.remove(playerUUID);
        combatOpponents.remove(playerUUID);

        Scheduler.Task task = combatTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
        }

        // No message is sent
    }

    public Player getCombatOpponent(Player player) {
        if (player == null) return null;

        UUID playerUUID = player.getUniqueId();
        if (!playersInCombat.containsKey(playerUUID)) return null;

        UUID opponentUUID = combatOpponents.get(playerUUID);
        if (opponentUUID == null) return null;

        return Bukkit.getPlayer(opponentUUID);
    }

    public boolean isInCombat(Player player) {
        if (player == null) return false;

        UUID playerUUID = player.getUniqueId();
        if (!playersInCombat.containsKey(playerUUID)) {
            return false;
        }

        long combatEndTime = playersInCombat.get(playerUUID);
        long currentTime = System.currentTimeMillis();

        if (currentTime > combatEndTime) {
            removeFromCombat(player);
            return false;
        }

        return true;
    }

    public int getRemainingCombatTime(Player player) {
        return getRemainingCombatTime(player, System.currentTimeMillis());
    }

    private int getRemainingCombatTime(Player player, long currentTime) {
        if (player == null) return 0;

        UUID playerUUID = player.getUniqueId();
        if (!playersInCombat.containsKey(playerUUID)) return 0;

        long endTime = playersInCombat.get(playerUUID);
        return (int) Math.ceil(Math.max(0, (endTime - currentTime) / 1000.0));
    }

    public void updateMutualCombat(Player player1, Player player2) {
        if (player1 != null && player1.isOnline() && player2 != null && player2.isOnline()) {
            tagPlayer(player1, player2);
            tagPlayer(player2, player1);
        }
    }

    // Ender pearl cooldown methods
    public void setEnderPearlCooldown(Player player) {
        if (player == null) return;

        // Only set cooldown if enabled in config
        if (!enderPearlEnabled) {
            return;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (worldEnderPearlSettings.containsKey(worldName) && !worldEnderPearlSettings.get(worldName)) {
            return; // Don't set cooldown in this world
        }

        // Check if we should only apply cooldown in combat
        if (enderPearlInCombatOnly && !isInCombat(player)) {
            return;
        }

        enderPearlCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + (enderPearlCooldownSeconds * 1000L));

        // Show real client-side item cooldown overlay (config duration).
        // Vanilla applies 1 second on throw, so we re-apply on later ticks to override.
        if (enderPearlCooldownTicks > 0) {
            player.setCooldown(Material.ENDER_PEARL, (int) enderPearlCooldownTicks);
            scheduleEnderPearlClientCooldownRefresh(player);
        }
    }

    public boolean isEnderPearlOnCooldown(Player player) {
        if (player == null) return false;

        // If all ender pearl cooldowns are disabled globally, always return false
        if (!enderPearlEnabled) {
            return false;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (worldEnderPearlSettings.containsKey(worldName) && !worldEnderPearlSettings.get(worldName)) {
            return false; // Cooldown disabled for this specific world
        }

        // Check if we should only apply cooldown in combat
        if (enderPearlInCombatOnly && !isInCombat(player)) {
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        if (!enderPearlCooldowns.containsKey(playerUUID)) {
            return false;
        }

        long cooldownEndTime = enderPearlCooldowns.get(playerUUID);
        long currentTime = System.currentTimeMillis();

        if (currentTime > cooldownEndTime) {
            enderPearlCooldowns.remove(playerUUID);
            return false;
        }

        return true;
    }

    public void refreshCombatOnPearlLand(Player player) {
        if (player == null || !refreshCombatOnPearlLand) return;

        // Only refresh if player is already in combat
        if (!isInCombat(player)) return;

        UUID playerUUID = player.getUniqueId();
        long newEndTime = System.currentTimeMillis() + (combatDurationSeconds * 1000L);
        long currentEndTime = playersInCombat.getOrDefault(playerUUID, 0L);

        // Only extend the combat time, don't shorten it
        if (newEndTime > currentEndTime) {
            playersInCombat.put(playerUUID, newEndTime);

            // Debug message if debug is enabled
            plugin.debug("Refreshed combat time for " + player.getName() + " due to pearl landing");
        }
    }

    public int getRemainingEnderPearlCooldown(Player player) {
        return getRemainingEnderPearlCooldown(player, System.currentTimeMillis());
    }

    private int getRemainingEnderPearlCooldown(Player player, long currentTime) {
        if (player == null) return 0;

        UUID playerUUID = player.getUniqueId();
        if (!enderPearlCooldowns.containsKey(playerUUID)) return 0;

        long endTime = enderPearlCooldowns.get(playerUUID);
        return (int) Math.ceil(Math.max(0, (endTime - currentTime) / 1000.0));
    }

    /** Ticks for {@link Player#setCooldown} (20 ticks = 1 second). */
    private int getRemainingEnderPearlCooldownTicks(Player player) {
        if (player == null) return 0;
        UUID playerUUID = player.getUniqueId();
        if (!enderPearlCooldowns.containsKey(playerUUID)) return 0;
        long endTime = enderPearlCooldowns.get(playerUUID);
        long remainingMs = endTime - System.currentTimeMillis();
        if (remainingMs <= 0) return 0;
        return Math.max(1, (int) Math.ceil(remainingMs / 50.0));
    }

    /** Re-applies client cooldown on later ticks so config duration overrides vanilla's 1 second. */
    private void scheduleEnderPearlClientCooldownRefresh(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        Runnable apply = () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            int t = getRemainingEnderPearlCooldownTicks(p);
            if (t > 0) {
                p.setCooldown(Material.ENDER_PEARL, t);
            }
        };
        Scheduler.runEntityTaskLater(player, apply, 1L);
        Scheduler.runEntityTaskLater(player, apply, 2L);
    }

    public boolean shouldDisableFlight(Player player) {
        if (player == null) return false;

        // If flight is enabled in combat by config or player isn't in combat, don't disable flight
        if (!disableFlightInCombat || !isInCombat(player)) {
            return false;
        }

        // Flight should be disabled - notify the player
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        plugin.getMessageService().sendMessage(player, "combat_fly_disabled", placeholders);

        return true;
    }

    public void setTridentCooldown(Player player) {
        if (player == null) return;

        // Only set cooldown if enabled in config
        if (!tridentEnabled) {
            return;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (worldTridentSettings.containsKey(worldName) && !worldTridentSettings.get(worldName)) {
            return; // Don't set cooldown in this world
        }

        // Check if we should only apply cooldown in combat
        if (tridentInCombatOnly && !isInCombat(player)) {
            return;
        }

        tridentCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + (tridentCooldownSeconds * 1000L));

        // Show real client-side item cooldown overlay (config duration).
        if (tridentCooldownTicks > 0) {
            player.setCooldown(Material.TRIDENT, (int) tridentCooldownTicks);
            scheduleTridentClientCooldownRefresh(player);
        }
    }

    public boolean isTridentOnCooldown(Player player) {
        if (player == null) return false;

        // If all trident cooldowns are disabled globally, always return false
        if (!tridentEnabled) {
            return false;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (worldTridentSettings.containsKey(worldName) && !worldTridentSettings.get(worldName)) {
            return false; // Cooldown disabled for this specific world
        }

        // Check if we should only apply cooldown in combat
        if (tridentInCombatOnly && !isInCombat(player)) {
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        if (!tridentCooldowns.containsKey(playerUUID)) {
            return false;
        }

        long cooldownEndTime = tridentCooldowns.get(playerUUID);
        long currentTime = System.currentTimeMillis();

        if (currentTime > cooldownEndTime) {
            tridentCooldowns.remove(playerUUID);
            return false;
        }

        return true;
    }

    public boolean isTridentBanned(Player player) {
        if (player == null) return false;

        // Check world-specific ban settings
        String worldName = player.getWorld().getName();
        return worldTridentBannedSettings.getOrDefault(worldName, false);
    }

    public void refreshCombatOnTridentLand(Player player) {
        if (player == null || !refreshCombatOnTridentLand) return;

        // Only refresh if player is already in combat
        if (!isInCombat(player)) return;

        UUID playerUUID = player.getUniqueId();
        long newEndTime = System.currentTimeMillis() + (combatDurationSeconds * 1000L);
        long currentEndTime = playersInCombat.getOrDefault(playerUUID, 0L);

        // Only extend the combat time, don't shorten it
        if (newEndTime > currentEndTime) {
            playersInCombat.put(playerUUID, newEndTime);

            // Debug message if debug is enabled
            plugin.debug("Refreshed combat time for " + player.getName() + " due to trident landing");
        }
    }

    /** Extends combat timer when a wind charge is thrown (if enabled and already tagged). */
    public void refreshCombatOnWindChargeThrow(Player player) {
        if (player == null || !refreshCombatOnWindChargeThrow) return;
        if (!isInCombat(player)) return;

        UUID playerUUID = player.getUniqueId();
        long newEndTime = System.currentTimeMillis() + (combatDurationSeconds * 1000L);
        long currentEndTime = playersInCombat.getOrDefault(playerUUID, 0L);

        if (newEndTime > currentEndTime) {
            playersInCombat.put(playerUUID, newEndTime);
            plugin.debug("Refreshed combat time for " + player.getName() + " due to wind charge throw");
        }
    }

    public int getRemainingTridentCooldown(Player player) {
        return getRemainingTridentCooldown(player, System.currentTimeMillis());
    }

    /** Ticks for {@link Player#setCooldown} (20 ticks = 1 second). */
    private int getRemainingTridentCooldownTicks(Player player) {
        if (player == null) return 0;
        UUID playerUUID = player.getUniqueId();
        if (!tridentCooldowns.containsKey(playerUUID)) return 0;
        long endTime = tridentCooldowns.get(playerUUID);
        long remainingMs = endTime - System.currentTimeMillis();
        if (remainingMs <= 0) return 0;
        return Math.max(1, (int) Math.ceil(remainingMs / 50.0));
    }

    /** Re-applies client cooldown on later ticks so config duration sticks. */
    private void scheduleTridentClientCooldownRefresh(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        Runnable apply = () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            int t = getRemainingTridentCooldownTicks(p);
            if (t > 0) {
                p.setCooldown(Material.TRIDENT, t);
            }
        };
        Scheduler.runEntityTaskLater(player, apply, 1L);
        Scheduler.runEntityTaskLater(player, apply, 2L);
    }

    private int getRemainingTridentCooldown(Player player, long currentTime) {
        if (player == null) return 0;

        UUID playerUUID = player.getUniqueId();
        if (!tridentCooldowns.containsKey(playerUUID)) return 0;

        long endTime = tridentCooldowns.get(playerUUID);
        return (int) Math.ceil(Math.max(0, (endTime - currentTime) / 1000.0));
    }


    public void shutdown() {
        // Cancel the global countdown task
        if (globalCountdownTask != null) {
            globalCountdownTask.cancel();
            globalCountdownTask = null;
        }

        // Cancel the cleanup task
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        // Cancel all individual tasks
        combatTasks.values().forEach(Scheduler.Task::cancel);
        combatTasks.clear();

        playersInCombat.clear();
        combatOpponents.clear();
        enderPearlCooldowns.clear();
        tridentCooldowns.clear();

        combatNametagManager.clearAllOnline();
        combatBossBars.values().forEach(BossBar::removeAll);
        combatBossBars.clear();
    }
}