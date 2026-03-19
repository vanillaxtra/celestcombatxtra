package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
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

    // Enforce no damage (keep projectile behavior).
    event.setDamage(0.0);
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

    try {
      return meta.getBasePotionData().getType().toString().equalsIgnoreCase("INSTANT_DAMAGE");
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean isHarmingPotionItem(ItemStack item) {
    if (item == null) return false;
    if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;

    try {
      return meta.getBasePotionData().getType().toString().equalsIgnoreCase("INSTANT_DAMAGE");
    } catch (Exception ignored) {
      return false;
    }
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

    // Best-effort reflection to support different server implementations.
    try {
      Method basePotionDataMethod = arrow.getClass().getMethod("getBasePotionData");
      Object potionData = basePotionDataMethod.invoke(arrow);
      if (potionData != null) {
        Method typeMethod = potionData.getClass().getMethod("getType");
        Object potionType = typeMethod.invoke(potionData);
        return potionType != null && potionType.toString().equalsIgnoreCase("INSTANT_DAMAGE");
      }
    } catch (Exception ignored) {
      // Fallback below
    }

    try {
      Method effectsMethod = arrow.getClass().getMethod("getEffects");
      Object effectsObj = effectsMethod.invoke(arrow);
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
      // can't determine
    }

    return false;
  }
}

