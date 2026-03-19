package com.shyamstudio.celestcombatXtra.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.api.events.*;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;

import java.util.Map;
import java.util.UUID;

public class CombatAPIImpl implements CombatAPI {
    
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    
    public CombatAPIImpl(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
    }
    
    @Override
    public boolean isInCombat(Player player) {
        return combatManager.isInCombat(player);
    }
    
    @Override
    public void tagPlayer(Player player, Player attacker, PreCombatEvent.CombatCause cause) {
        if (player == null || attacker == null) return;
        
        PreCombatEvent preCombatEvent = new PreCombatEvent(player, attacker, cause);
        Bukkit.getPluginManager().callEvent(preCombatEvent);
        
        if (preCombatEvent.isCancelled()) {
            return;
        }
        
        boolean wasAlreadyInCombat = isInCombat(player);
        combatManager.tagPlayer(player, attacker);
        
        CombatEvent combatEvent = new CombatEvent(player, attacker, cause, getCombatDuration(), wasAlreadyInCombat);
        Bukkit.getPluginManager().callEvent(combatEvent);
    }
    
    @Override
    public void removeFromCombat(Player player) {
        if (player == null || !isInCombat(player)) return;
        
        Player lastAttacker = getCombatOpponent(player);
        long totalCombatTime = System.currentTimeMillis() - (combatManager.getPlayersInCombat().get(player.getUniqueId()) - (getCombatDuration() * 1000));
        
        combatManager.removeFromCombat(player);
        
        CombatEndEvent combatEndEvent = new CombatEndEvent(player, lastAttacker, CombatEndEvent.CombatEndReason.EXPIRED, totalCombatTime);
        Bukkit.getPluginManager().callEvent(combatEndEvent);
    }
    
    @Override
    public void removeFromCombatSilently(Player player) {
        if (player == null || !isInCombat(player)) return;
        
        Player lastAttacker = getCombatOpponent(player);
        long totalCombatTime = System.currentTimeMillis() - (combatManager.getPlayersInCombat().get(player.getUniqueId()) - (getCombatDuration() * 1000));
        
        combatManager.removeFromCombatSilently(player);
        
        CombatEndEvent combatEndEvent = new CombatEndEvent(player, lastAttacker, CombatEndEvent.CombatEndReason.ADMIN_REMOVE, totalCombatTime);
        Bukkit.getPluginManager().callEvent(combatEndEvent);
    }
    
    @Override
    public Player getCombatOpponent(Player player) {
        return combatManager.getCombatOpponent(player);
    }
    
    @Override
    public int getRemainingCombatTime(Player player) {
        return combatManager.getRemainingCombatTime(player);
    }
    
    @Override
    public Map<UUID, Long> getPlayersInCombat() {
        return combatManager.getPlayersInCombat();
    }
    
    @Override
    public boolean isEnderPearlOnCooldown(Player player) {
        return combatManager.isEnderPearlOnCooldown(player);
    }
    
    @Override
    public void setEnderPearlCooldown(Player player) {
        if (player == null) return;
        
        long remainingCooldown = getRemainingEnderPearlCooldown(player);
        boolean inCombat = isInCombat(player);
        
        EnderPearlEvent event = new EnderPearlEvent(player, remainingCooldown, inCombat);
        Bukkit.getPluginManager().callEvent(event);
        
        if (!event.isCancelled()) {
            combatManager.setEnderPearlCooldown(player);
        }
    }
    
    @Override
    public int getRemainingEnderPearlCooldown(Player player) {
        return combatManager.getRemainingEnderPearlCooldown(player);
    }
    
    @Override
    public boolean isTridentOnCooldown(Player player) {
        return combatManager.isTridentOnCooldown(player);
    }
    
    @Override
    public void setTridentCooldown(Player player) {
        if (player == null) return;
        
        long remainingCooldown = getRemainingTridentCooldown(player);
        boolean inCombat = isInCombat(player);
        boolean banned = isTridentBanned(player);
        
        TridentEvent event = new TridentEvent(player, remainingCooldown, inCombat, banned);
        Bukkit.getPluginManager().callEvent(event);
        
        if (!event.isCancelled()) {
            combatManager.setTridentCooldown(player);
        }
    }
    
    @Override
    public int getRemainingTridentCooldown(Player player) {
        return combatManager.getRemainingTridentCooldown(player);
    }
    
    @Override
    public boolean isTridentBanned(Player player) {
        return combatManager.isTridentBanned(player);
    }
    
    @Override
    public void refreshCombatOnPearlLand(Player player) {
        combatManager.refreshCombatOnPearlLand(player);
    }
    
    @Override
    public void refreshCombatOnTridentLand(Player player) {
        combatManager.refreshCombatOnTridentLand(player);
    }
    
    @Override
    public boolean shouldDisableFlight(Player player) {
        return combatManager.shouldDisableFlight(player);
    }
    
    @Override
    public void punishCombatLogout(Player player) {
        if (player == null || !isInCombat(player)) return;
        
        Player lastAttacker = getCombatOpponent(player);
        long remainingTime = getRemainingCombatTime(player);
        
        CombatLogEvent event = new CombatLogEvent(player, lastAttacker, remainingTime);
        Bukkit.getPluginManager().callEvent(event);
        
        if (!event.isCancelled() && event.shouldPunish()) {
            combatManager.punishCombatLogout(player);
        }
        
        if (!event.isCancelled()) {
            CombatEndEvent endEvent = new CombatEndEvent(player, lastAttacker, CombatEndEvent.CombatEndReason.LOGOUT, 0);
            Bukkit.getPluginManager().callEvent(endEvent);
        }
    }
    
    @Override
    public long getCombatDuration() {
        return plugin.getTimeFromConfig("combat.duration", "20s") / 20;
    }
    
    @Override
    public long getEnderPearlCooldownDuration() {
        return plugin.getTimeFromConfig(getEnderPearlCooldownPath("duration"), "10s") / 20;
    }
    
    @Override
    public long getTridentCooldownDuration() {
        return plugin.getTimeFromConfig(getTridentCooldownPath("duration"), "10s") / 20;
    }
    
    @Override
    public boolean isFlightDisabledInCombat() {
        return plugin.getConfig().getBoolean("combat.disable_flight", true);
    }
    
    @Override
    public boolean isEnderPearlCooldownEnabledInWorld(String worldName) {
        String enabledPath = getEnderPearlCooldownPath("enabled");
        if (!plugin.getConfig().getBoolean(enabledPath, true)) {
            return false;
        }

        String worldsPath = getEnderPearlCooldownPath("worlds");
        if (plugin.getConfig().isConfigurationSection(worldsPath)) {
            return plugin.getConfig().getBoolean(worldsPath + "." + worldName, true);
        }
        
        return true;
    }
    
    @Override
    public boolean isTridentCooldownEnabledInWorld(String worldName) {
        String enabledPath = getTridentCooldownPath("enabled");
        if (!plugin.getConfig().getBoolean(enabledPath, true)) {
            return false;
        }

        String worldsPath = getTridentCooldownPath("worlds");
        if (plugin.getConfig().isConfigurationSection(worldsPath)) {
            return plugin.getConfig().getBoolean(worldsPath + "." + worldName, true);
        }

        return true;
    }
    
    @Override
    public boolean isTridentBannedInWorld(String worldName) {
        if (plugin.getConfig().isConfigurationSection("trident.banned_worlds")) {
            return plugin.getConfig().getBoolean("trident.banned_worlds." + worldName, false);
        }
        
        return false;
    }

    private String getTridentCooldownPath(String suffix) {
        String nestedPath = "trident.cooldown." + suffix;
        if (plugin.getConfig().contains(nestedPath) || plugin.getConfig().isConfigurationSection(nestedPath)) {
            return nestedPath;
        }
        return "trident_cooldown." + suffix;
    }

    private String getEnderPearlCooldownPath(String suffix) {
        String nestedPath = "enderpearl.cooldown." + suffix;
        if (plugin.getConfig().contains(nestedPath) || plugin.getConfig().isConfigurationSection(nestedPath)) {
            return nestedPath;
        }
        return "enderpearl_cooldown." + suffix;
    }
}
