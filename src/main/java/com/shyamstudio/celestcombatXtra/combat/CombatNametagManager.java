package com.shyamstudio.celestcombatXtra.combat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.Component;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * applies scoreboard team prefix/suffix while a player is combat-tagged.
 * saves and restores the player's original team when combat ends so other
 * plugins (luckperms, tab, etc.) keep their prefixes/suffixes.
 */
public final class CombatNametagManager {

  private final CelestCombatPro plugin;
  private final CombatManager combatManager;

  // original team name per player, saved on first combat nametag apply
  private final Map<UUID, String> savedOriginalTeams = new ConcurrentHashMap<>();

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

    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("opponent", opponent != null ? opponent.getName() : "?");
    placeholders.put("opponent_display", opponent != null ? opponent.getDisplayName() : "?");
    placeholders.put("time", String.valueOf(seconds));
    placeholders.put("time_seconds", String.valueOf(seconds));

    Component prefix = plugin.getLanguageManager().getNametagPrefix(placeholders);
    Component suffix = plugin.getLanguageManager().getNametagSuffix(placeholders);

    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
    String teamName = teamId(player);
    String entry = player.getName();

    // save the original team before we pull the player into the combat team
    if (!savedOriginalTeams.containsKey(player.getUniqueId())) {
      Team originalTeam = board.getEntryTeam(entry);
      if (originalTeam != null && !originalTeam.getName().equals(teamName)) {
        savedOriginalTeams.put(player.getUniqueId(), originalTeam.getName());
      }
    }

    Team team = board.getTeam(teamName);
    if (team == null) {
      team = board.registerNewTeam(teamName);
    }

    if (!team.hasEntry(entry)) {
      team.addEntry(entry);
    }
    team.prefix(prefix);
    team.suffix(suffix);
  }

  public void clear(Player player) {
    if (player == null) return;
    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
    Team combatTeam = board.getTeam(teamId(player));
    if (combatTeam != null) {
      combatTeam.removeEntry(player.getName());
      if (combatTeam.getEntries().isEmpty()) {
        combatTeam.unregister();
      }
    }

    // restore the original team
    String savedName = savedOriginalTeams.remove(player.getUniqueId());
    if (savedName != null) {
      Team originalTeam = board.getTeam(savedName);
      if (originalTeam != null) {
        originalTeam.addEntry(player.getName());
      }
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
}
