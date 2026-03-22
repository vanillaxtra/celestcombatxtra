package com.shyamstudio.celestcombatXtra.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;

/**
 * PlaceholderAPI expansion for CelestCombat-Xtra.
 * Provides combat state, cooldowns, and opponent information.
 */
public final class CelestCombatPlaceholders extends PlaceholderExpansion {

    private final CelestCombatPro plugin;

    public CelestCombatPlaceholders(CelestCombatPro plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getAuthor() {
        return "RaviRai";
    }

    @Override
    public String getIdentifier() {
        return "celestcombat";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (!(offlinePlayer instanceof Player player) || !player.isOnline()) {
            return getDefaultFor(params);
        }

        CombatManager cm = plugin.getCombatManager();
        if (cm == null) return getDefaultFor(params);

        return switch (params.toLowerCase()) {
            case "in_combat" -> cm.isInCombat(player) ? "true" : "false";
            case "time_left", "time" -> String.valueOf(cm.getRemainingCombatTime(player));
            case "time_seconds" -> String.valueOf(cm.getRemainingCombatTime(player));
            case "opponent" -> {
                Player opponent = cm.getCombatOpponent(player);
                yield opponent != null ? opponent.getName() : "";
            }
            case "opponent_display" -> {
                Player opponent = cm.getCombatOpponent(player);
                yield opponent != null ? opponent.getPlayerListName() : "";
            }
            case "pearl_cooldown", "pearl_time" -> String.valueOf(cm.getRemainingEnderPearlCooldown(player));
            case "trident_cooldown", "trident_time" -> String.valueOf(cm.getRemainingTridentCooldown(player));
            case "wind_cooldown", "wind_time" -> String.valueOf(getWindCooldown(player));
            case "pearl_ready" -> cm.getRemainingEnderPearlCooldown(player) <= 0 ? "true" : "false";
            case "trident_ready" -> cm.getRemainingTridentCooldown(player) <= 0 ? "true" : "false";
            case "wind_ready" -> getWindCooldown(player) <= 0 ? "true" : "false";
            default -> null;
        };
    }

    private int getWindCooldown(Player player) {
        if (!(plugin instanceof com.shyamstudio.celestcombatXtra.CelestCombatXtra ccx)) return 0;
        var icm = ccx.getItemCooldownManager();
        if (icm == null) return 0;
        return icm.getRemainingWindChargeCooldown(player);
    }

    private String getDefaultFor(String params) {
        return switch (params.toLowerCase()) {
            case "in_combat", "pearl_ready", "trident_ready", "wind_ready" -> "false";
            case "time_left", "time", "time_seconds", "pearl_cooldown", "pearl_time",
                    "trident_cooldown", "trident_time", "wind_cooldown", "wind_time" -> "0";
            case "opponent", "opponent_display" -> "";
            default -> null;
        };
    }
}
