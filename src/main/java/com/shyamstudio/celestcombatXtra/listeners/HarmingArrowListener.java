package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;

import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import org.bukkit.potion.PotionType;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Phase 1 harming arrow control (no-damage mode).
 *
 * Config keys:
 * - harming_arrows.no_damage
 * - harming_arrows.bow_crossbow_allow
 * - harming_arrows.dispenser_allow
 */
public final class HarmingArrowListener implements Listener {

  private final CelestCombatPro plugin;

  private final boolean noDamage;
  private final boolean bowsAllowHarming;
  private final boolean crossbowsAllowHarming;
  private final boolean dispensersAllowHarming;
  private final boolean preventPotions;

  public HarmingArrowListener(CelestCombatPro plugin) {
    this.plugin = plugin;
    boolean legacyBowCrossbowAllow = plugin.getConfig().getBoolean("harming_arrows.bow_crossbow_allow", true);
    boolean legacyDispenserAllow = plugin.getConfig().getBoolean("harming_arrows.dispenser_allow", true);

    this.noDamage = plugin.getConfig().getBoolean("harming.no_damage", plugin.getConfig().getBoolean("harming_arrows.no_damage", true));
    this.bowsAllowHarming = plugin.getConfig().getBoolean("harming.bows_allow_harming", legacyBowCrossbowAllow);
    this.crossbowsAllowHarming = plugin.getConfig().getBoolean("harming.crossbows_allow_harming", legacyBowCrossbowAllow);
    this.dispensersAllowHarming = plugin.getConfig().getBoolean("harming.dispensers_allow_harming", legacyDispenserAllow);
    this.preventPotions = plugin.getConfig().getBoolean("harming.prevent_potions", true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityShootBow(EntityShootBowEvent event) {
    Entity shooterEntity = event.getEntity();
    if (!(shooterEntity instanceof Player player)) return;
    if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;

    if (!isHarmingArrowProjectile(arrow)) return;

    Material weaponType = event.getBow() != null ? event.getBow().getType() : Material.AIR;
    boolean allowed;
    if (weaponType == Material.CROSSBOW) {
      allowed = crossbowsAllowHarming;
    } else if (weaponType == Material.BOW) {
      allowed = bowsAllowHarming;
    } else {
      allowed = bowsAllowHarming; // Best-effort default
    }

    if (allowed) return;

    event.setCancelled(true);
    plugin.getMessageService().sendMessage(player, "harming_disallowed");
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDispenser(BlockDispenseEvent event) {
    ItemStack item = event.getItem();
    if (item == null || item.getType() == Material.AIR) return;

    if (preventPotions && isHarmingPotionItem(item)) {
      event.setCancelled(true);
      return;
    }

    if (dispensersAllowHarming) return;

    if (!isHarmingArrowItem(item)) return;
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent event) {
    if (!noDamage) return;
    if (!(event.getDamager() instanceof AbstractArrow arrow)) return;
    if (!(event.getEntity() instanceof Player victim)) return;

    if (!isHarmingArrowProjectile(arrow)) return;

    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onCrossbowLoad(EntityLoadCrossbowEvent event) {
    if (crossbowsAllowHarming) return;
    if (!(event.getEntity() instanceof Player player)) return;

    // Crossbow consumes arrows: opposite hand first, then hotbar 0-8. Cancel only if the first arrow would be harming.
    ItemStack firstArrow = findFirstCrossbowArrow(player, event.getHand());
    if (firstArrow != null && isHarmingArrowItem(firstArrow)) {
      event.setCancelled(true);
      plugin.getMessageService().sendMessage(player, "harming_disallowed");
      player.updateInventory();
    }
  }

  /** First arrow in crossbow consumption order: opposite hand first, then hotbar slots 0-8. */
  private ItemStack findFirstCrossbowArrow(Player player, EquipmentSlot crossbowHand) {
    ItemStack otherHand = crossbowHand == EquipmentSlot.HAND
        ? player.getInventory().getItemInOffHand()
        : player.getInventory().getItemInMainHand();
    if (isArrowItem(otherHand)) return otherHand;
    for (int i = 0; i < 9; i++) {
      ItemStack s = player.getInventory().getItem(i);
      if (isArrowItem(s)) return s;
    }
    return null;
  }

  private boolean isArrowItem(ItemStack item) {
    if (item == null || item.getType() == Material.AIR) return false;
    return item.getType() == Material.ARROW || item.getType() == Material.TIPPED_ARROW
        || item.getType() == Material.SPECTRAL_ARROW;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPotionConsume(PlayerItemConsumeEvent event) {
    if (!preventPotions) return;
    ItemStack item = event.getItem();
    if (!isHarmingPotionItem(item)) return;

    event.setCancelled(true);
    plugin.getMessageService().sendMessage(event.getPlayer(), "harming_disallowed");
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPotionLaunch(ProjectileLaunchEvent event) {
    if (!preventPotions) return;
    if (!(event.getEntity() instanceof ThrownPotion potion)) return;

    if (!isHarmingThrownPotion(potion)) return;

    // Best-effort: only cancel player-thrown harming potions (dispensers are handled elsewhere)
    if (event.getEntity().getShooter() instanceof Player player) {
      event.setCancelled(true);
      plugin.getMessageService().sendMessage(player, "harming_disallowed");
    } else {
      event.setCancelled(true);
    }
  }

  private boolean isHarmingArrowItem(ItemStack item) {
    if (item == null) return false;
    if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;
    return getIsHarmingFromMeta(meta);
  }

  private boolean isHarmingPotionItem(ItemStack item) {
    if (item == null) return false;
    if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;
    return getIsHarmingFromMeta(meta);
  }

  private boolean getIsHarmingFromMeta(PotionMeta meta) {
    try {
      PotionType type = meta.getBasePotionType();
      return isHarmingPotionType(type);
    } catch (NoSuchMethodError | Exception e) {
      try {
        Object type = meta.getBasePotionData().getType();
        return type != null && (type.toString().equalsIgnoreCase("INSTANT_DAMAGE")
            || type.toString().toUpperCase().contains("HARMING"));
      } catch (Exception ignored) {
        return false;
      }
    }
  }

  private boolean isHarmingPotionType(PotionType type) {
    if (type == null) return false;
    return type == PotionType.HARMING || type == PotionType.STRONG_HARMING;
  }

  private boolean isHarmingThrownPotion(ThrownPotion potion) {
    if (potion == null) return false;

    try {
      // Best-effort reflection to detect instant damage potions across server versions.
      try {
        Method potionTypeMethod = potion.getClass().getMethod("getPotionType");
        Object potionTypeObj = potionTypeMethod.invoke(potion);
        return potionTypeObj != null && potionTypeObj.toString().equalsIgnoreCase("INSTANT_DAMAGE");
      } catch (NoSuchMethodException ignored) {
        // continue to fallback below
      }

      Method effectsMethod = potion.getClass().getMethod("getEffects");
      Object effectsObj = effectsMethod.invoke(potion);
      if (effectsObj instanceof Collection<?> effects) {
        for (Object eff : effects) {
          try {
            Method getType = eff.getClass().getMethod("getType");
            Object type = getType.invoke(eff);
            if (type != null && type.toString().equalsIgnoreCase("INSTANT_DAMAGE")) {
              return true;
            }
          } catch (Exception ignored) {
            // ignore this effect
          }
        }
      }
    } catch (Exception ignored) {
      return false;
    }

    return false;
  }

  private boolean isHarmingArrowProjectile(AbstractArrow arrow) {
    if (arrow == null) return false;

    // Primary: check the arrow's item (most reliable across versions)
    ItemStack arrowItem = arrow.getItem();
    if (arrowItem != null && isHarmingArrowItem(arrowItem)) return true;

    if (!(arrow instanceof Arrow tippedArrow)) return false;

    // 1.20.5+: use getBasePotionType()
    try {
      PotionType type = tippedArrow.getBasePotionType();
      if (isHarmingPotionType(type)) return true;
    } catch (NoSuchMethodError | Exception ignored) {
    }

    // Check custom effects (HARM/INSTANT_DAMAGE)
    try {
      PotionEffectType harm = PotionEffectType.INSTANT_DAMAGE;
      if (harm != null && tippedArrow.hasCustomEffect(harm)) return true;
    } catch (Exception ignored) {
    }

    // Fallback: reflection for older servers
    try {
      Method m = tippedArrow.getClass().getMethod("getBasePotionData");
      Object potionData = m.invoke(tippedArrow);
      if (potionData != null) {
        Method typeMethod = potionData.getClass().getMethod("getType");
        Object pt = typeMethod.invoke(potionData);
        if (pt != null) {
          String s = pt.toString().toUpperCase();
          return s.contains("INSTANT_DAMAGE") || s.contains("HARMING");
        }
      }
    } catch (Exception ignored) {
    }

    return false;
  }
}

