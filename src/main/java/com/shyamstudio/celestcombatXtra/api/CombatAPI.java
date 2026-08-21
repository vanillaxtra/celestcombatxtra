package com.shyamstudio.celestcombatXtra.api;

import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.api.events.PreCombatEvent;

import java.util.Map;
import java.util.UUID;

public interface CombatAPI {
    
    boolean isInCombat(Player player);
    
    void tagPlayer(Player player, Player attacker, PreCombatEvent.CombatCause cause);
    
    void removeFromCombat(Player player);
    
    void removeFromCombatSilently(Player player);

    /**
     * Ends combat immediately for any opponent of {@code player} whose remaining
     * tracked opponents are now all offline, instead of leaving them tagged until
     * the full combat duration times out. Call whenever {@code player} permanently
     * leaves combat outside the normal timeout — disconnecting (quit or kick) or
     * dying to something other than a tracked opponent (e.g. a mob) — while still
     * marked as in combat.
     */
    void handlePlayerCombatExit(Player player);

    Player getCombatOpponent(Player player);
    
    int getRemainingCombatTime(Player player);
    
    Map<UUID, Long> getPlayersInCombat();
    
    boolean isEnderPearlOnCooldown(Player player);
    
    void setEnderPearlCooldown(Player player);
    
    int getRemainingEnderPearlCooldown(Player player);
    
    boolean isTridentOnCooldown(Player player);
    
    void setTridentCooldown(Player player);
    
    int getRemainingTridentCooldown(Player player);
    
    boolean isTridentBanned(Player player);
    
    void refreshCombatOnPearlLand(Player player);
    
    void refreshCombatOnTridentLand(Player player);
    
    boolean shouldDisableFlight(Player player);
    
    void punishCombatLogout(Player player);
    
    long getCombatDuration();
    
    long getEnderPearlCooldownDuration();
    
    long getTridentCooldownDuration();
    
    boolean isFlightDisabledInCombat();
    
    boolean isEnderPearlCooldownEnabledInWorld(String worldName);
    
    boolean isTridentCooldownEnabledInWorld(String worldName);
    
    boolean isTridentBannedInWorld(String worldName);

    /**
     * Effective PVP-enabled state for a player (accounts for the activation/deactivation
     * warmup state machine - see PvpToggleManager). Not-yet-loaded players are off.
     */
    boolean isPvpEnabled(Player player);
}
