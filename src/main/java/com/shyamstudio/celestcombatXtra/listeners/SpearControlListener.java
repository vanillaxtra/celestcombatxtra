package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager.CooldownKey;
import com.shyamstudio.celestcombatXtra.cooldown.UseCooldowns;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.Map;

/**
 * Spear (1.21+) lunge cooldown and optional damage disable.
 */
public final class SpearControlListener implements Listener {

  private static final Material SPEAR_MATERIAL = Material.matchMaterial("SPEAR");
  private static final CooldownKey LUNGE_KEY =
      SPEAR_MATERIAL != null ? new CooldownKey(SPEAR_MATERIAL, "lunge") : null;

  private final CelestCombatPro plugin;
  private final ItemCooldownManager cooldownManager;

  public SpearControlListener(CelestCombatPro plugin, ItemCooldownManager cooldownManager) {
    this.plugin = plugin;
    this.cooldownManager = cooldownManager;
  }

  private boolean master() {
    return plugin.getConfig().getBoolean("spear_control.enabled", false);
  }

  private String bypass() {
    return plugin.getConfig().getString("spear_control.bypass_permission", "celestcombatxtra.bypass.spear_control");
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSpearInteract(PlayerInteractEvent event) {
    if (SPEAR_MATERIAL == null || LUNGE_KEY == null) return;
    if (!master()) return;
    if (!plugin.getConfig().getBoolean("spear_control.lunge_cooldown.enabled", true)) return;
    if (event.getHand() != EquipmentSlot.HAND) return;
    if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

    Player player = event.getPlayer();
    if (player.hasPermission(bypass())) return;

    ItemStack item = event.getItem();
    if (item == null || item.getType() != SPEAR_MATERIAL) return;

    if (cooldownManager.isGeneralItemOnCooldown(player, LUNGE_KEY)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingGeneralItemCooldown(player, LUNGE_KEY);
      int rt = cooldownManager.getRemainingGeneralItemCooldownTicks(player, LUNGE_KEY);
      if (rt > 0) {
        player.setCooldown(SPEAR_MATERIAL, rt);
      }
      UseCooldowns.applyUseCooldownIfKnown(player, SPEAR_MATERIAL);
      String bar = plugin.getLanguageManager().getActionBar("spear_lunge_cooldown",
          Map.of("time", String.valueOf(remaining)));
      if (bar != null) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(bar));
      }
      return;
    }

    String durStr = plugin.getConfig().getString("spear_control.lunge_cooldown.duration", "1s");
    long durationTicks = plugin.getTimeFormatter().parseTimeToTicks(durStr, 20L);
    long ms = Math.max(0L, durationTicks * 50L);
    if (ms <= 0) return;

    long finalMs = ms;
    Scheduler.runEntityTaskLater(player, () -> {
      if (!player.isOnline()) return;
      cooldownManager.startGeneralCooldown(player, LUNGE_KEY, finalMs);
    }, 1L);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSpearDamage(EntityDamageByEntityEvent event) {
    if (SPEAR_MATERIAL == null) return;
    if (!master()) return;
    if (!plugin.getConfig().getBoolean("spear_control.disable_damage", false)) return;

    if (!isSpearDamage(event)) return;

    Player attacker = resolveAttacker(event);
    if (attacker != null && attacker.hasPermission(bypass())) return;

    event.setCancelled(true);
  }

  private Player resolveAttacker(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player p) {
      return p;
    }
    if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
      return p;
    }
    return null;
  }

  private boolean isSpearDamage(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player p) {
      ItemStack hand = p.getInventory().getItemInMainHand();
      return hand.getType() == SPEAR_MATERIAL;
    }
    if (event.getDamager() instanceof Projectile proj) {
      return proj.getType().name().contains("SPEAR");
    }
    return false;
  }
}
