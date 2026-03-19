package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.configs.WindchargeConfigPaths;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.cooldown.UseCooldowns;
import com.shyamstudio.celestcombatXtra.language.ColorUtil;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.Map;

/**
 * Wind charge cooldown + optional disable in/out of combat.
 *
 * <p>Config is re-read on {@link #reloadFromConfig()} (server /cc reload).
 */
public final class WindChargeListener implements Listener {

  private final CelestCombatPro plugin;
  private final ItemCooldownManager cooldownManager;

  private boolean enabled;
  private boolean inCombatOnly;
  private boolean disableInCombat;
  private boolean disableOutOfCombat;
  private long cooldownMs;
  private boolean refreshCombatOnThrow;

  public WindChargeListener(CelestCombatPro plugin, ItemCooldownManager cooldownManager) {
    this.plugin = plugin;
    this.cooldownManager = cooldownManager;
    reloadFromConfig();
  }

  /** Call after {@code plugin.reloadConfig()} so duration / toggles apply without restart. */
  public void reloadFromConfig() {
    String root = WindchargeConfigPaths.root(plugin.getConfig());
    this.enabled = plugin.getConfig().getBoolean(root + ".enabled", true);
    this.inCombatOnly = plugin.getConfig().getBoolean(root + ".in_combat_only", false);
    this.disableInCombat = plugin.getConfig().getBoolean(root + ".cooldown.disable_in_combat", false);
    this.disableOutOfCombat = plugin.getConfig().getBoolean(root + ".cooldown.disable_out_of_combat", false);
    this.refreshCombatOnThrow = plugin.getConfig().getBoolean(root + ".refresh_combat_on_throw", false);

    Object durationObj = plugin.getConfig().get(root + ".cooldown.duration");
    if (durationObj == null) {
      durationObj = plugin.getConfig().get(root + ".duration");
    }
    if (durationObj instanceof Number n) {
      this.cooldownMs = Math.max(0L, n.longValue() * 50L);
    } else {
      String durationStr = durationObj != null ? durationObj.toString().trim() : "10s";
      long durationTicks = plugin.getTimeFormatter().parseTimeToTicks(durationStr, 10L * 20L);
      this.cooldownMs = durationTicks * 50L;
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onWindChargeInteract(PlayerInteractEvent event) {
    if (!enabled) return;
    if (event.getHand() == null) return;

    Player player = event.getPlayer();
    ItemStack item = event.getItem();
    Action action = event.getAction();
    if (item == null || item.getType() != Material.WIND_CHARGE) return;
    if (!(action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) return;

    boolean inCombat = plugin.getCombatManager().isInCombat(player);
    if (inCombatOnly && !inCombat) return;
    if ((inCombat && disableInCombat) || (!inCombat && disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(player, Material.WIND_CHARGE);
      sendRestrictionFeedback(
          player,
          inCombat ? "windcharge_disabled_in_combat" : "windcharge_disabled_out_of_combat"
      );
      return;
    }

    if (cooldownManager.isWindChargeOnCooldown(player)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingWindChargeCooldown(player);
      int remainingTicks = cooldownManager.getRemainingWindChargeCooldownTicks(player);
      if (remainingTicks > 0) {
        player.setCooldown(Material.WIND_CHARGE, remainingTicks);
      }
      if (!shouldSkipCooldownActionBar(player)) {
        sendWindChargeCooldownActionBar(player, remaining);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onWindChargeLaunch(ProjectileLaunchEvent event) {
    if (!enabled) return;
    if (!(event.getEntity() instanceof WindCharge)) return;
    if (!(event.getEntity().getShooter() instanceof Player player)) return;

    boolean inCombat = plugin.getCombatManager().isInCombat(player);
    if (inCombatOnly && !inCombat) return;
    if ((inCombat && disableInCombat) || (!inCombat && disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(player, Material.WIND_CHARGE);
      sendRestrictionFeedback(
          player,
          inCombat ? "windcharge_disabled_in_combat" : "windcharge_disabled_out_of_combat"
      );
      return;
    }

    if (cooldownManager.isWindChargeOnCooldown(player)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingWindChargeCooldown(player);
      int remainingTicks = cooldownManager.getRemainingWindChargeCooldownTicks(player);
      if (remainingTicks > 0) {
        player.setCooldown(Material.WIND_CHARGE, remainingTicks);
      }
      if (!shouldSkipCooldownActionBar(player)) {
        sendWindChargeCooldownActionBar(player, remaining);
      }
      return;
    }

    cooldownManager.startWindChargeCooldown(player, cooldownMs);
    plugin.getCombatManager().refreshCombatOnWindChargeThrow(player);
  }

  private void sendRestrictionFeedback(Player player, String key) {
    if (player == null || key == null) return;
    plugin.getMessageService().sendMessage(player, key);
  }

  private void sendWindChargeCooldownActionBar(Player player, int remainingSeconds) {
    if (player == null || !player.isOnline()) return;
    String line = plugin.getLanguageManager().getActionBar(
        "windcharge_cooldown", Map.of("time", String.valueOf(remainingSeconds)));
    if (line != null) {
      player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
          TextComponent.fromLegacyText(ColorUtil.translateHexColorCodes(line)));
    }
  }

  private boolean shouldSkipCooldownActionBar(Player player) {
    if (player == null) return true;
    return plugin.getCombatManager().isInCombat(player)
        || plugin.getCombatManager().isEnderPearlOnCooldown(player)
        || plugin.getCombatManager().isTridentOnCooldown(player);
  }
}
