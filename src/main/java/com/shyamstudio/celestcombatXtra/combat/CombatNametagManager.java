package com.shyamstudio.celestcombatXtra.combat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;

/**
 * Applies scoreboard team prefix/suffix while a player is combat-tagged.
 * <p>
 * Placeholders: {@code %opponent%}, {@code %opponent_display%}, {@code %time%} (remaining seconds).
 * Use {@code &} for legacy color codes.
 */
public final class CombatNametagManager {

  private final CelestCombatPro plugin;
  private final CombatManager combatManager;

  public CombatNametagManager(CelestCombatPro plugin, CombatManager combatManager) {
    this.plugin = plugin;
    this.combatManager = combatManager;
  }

  private boolean enabled() {
    return plugin.getConfig().getBoolean("combat.nametag.enabled", false);
  }

  public void refresh(Player player) {
    if (player == null || !player.isOnline()) return;
    if (!enabled()) {
      clear(player);
      return;
    }
    if (!combatManager.isInCombat(player)) {
      clear(player);
      return;
    }

    Player opponent = combatManager.getCombatOpponent(player);
    int seconds = combatManager.getRemainingCombatTime(player);

    String prefixRaw = plugin.getConfig().getString("combat.nametag.prefix", "");
    String suffixRaw = plugin.getConfig().getString("combat.nametag.suffix", "");

    String prefix = trimTeamField(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(prefixRaw, opponent, seconds)));
    String suffix = trimTeamField(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(suffixRaw, opponent, seconds)));

    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
    String teamName = teamId(player);
    Team team = board.getTeam(teamName);
    if (team == null) {
      team = board.registerNewTeam(teamName);
    }

    String entry = player.getName();
    if (!team.hasEntry(entry)) {
      team.addEntry(entry);
    }
    team.setPrefix(prefix);
    team.setSuffix(suffix);
  }

  public void clear(Player player) {
    if (player == null) return;
    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
    Team team = board.getTeam(teamId(player));
    if (team == null) return;
    team.removeEntry(player.getName());
    if (team.getEntries().isEmpty()) {
      team.unregister();
    }
  }

  public void clearAllOnline() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      clear(p);
    }
  }

  private static String teamId(Player player) {
    return "ccxnt_" + player.getUniqueId().toString().replace("-", "");
  }

  private static String trimTeamField(String s) {
    if (s == null) return "";
    // Legacy team prefix/suffix length limits on older clients; trim defensively.
    return s.length() > 64 ? s.substring(0, 64) : s;
  }

  private static String applyPlaceholders(String raw, Player opponent, int seconds) {
    if (raw == null) return "";
    String opp = opponent != null ? opponent.getName() : "?";
    String oppDisp = opponent != null ? opponent.getDisplayName() : "?";
    return raw
        .replace("%opponent%", opp)
        .replace("%opponent_display%", oppDisp)
        .replace("%time%", String.valueOf(seconds))
        .replace("%time_seconds%", String.valueOf(seconds));
  }
}
