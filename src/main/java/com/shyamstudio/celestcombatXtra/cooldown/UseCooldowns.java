package com.shyamstudio.celestcombatXtra.cooldown;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla "use cooldown" overlays (what the client shows when you consume/use an item).
 * <p>
 * This is used when we cancel item usage due to combat rules, so players still get
 * the expected cooldown overlay behavior instead of only action bar countdowns.
 */
public final class UseCooldowns {

  private static final Map<Material, Integer> USE_COOLDOWN_TICKS = new HashMap<>();

  static {
    // 1 second / 20 ticks
    add("ENDER_PEARL", 20);
    add("CHORUS_FRUIT", 20);
    add("ICE_BOMB", 20); // Java might not have this material; matchMaterial handles it.
    add("WIND_CHARGE", 20);
    add("SPEAR", 20);
    add("FIREWORK_ROCKET", 20);

    // 7 seconds / 140 ticks
    add("GOAT_HORN", 140);
    add("COPPER_HORN", 140);

    // 5 seconds / 100 ticks
    add("SHIELD", 100);

    // 0.2 seconds / 4 ticks
    add("SNOWBALL", 4);
    add("EGG", 4);
  }

  private static void add(String materialName, int ticks) {
    Material material = Material.matchMaterial(materialName);
    if (material != null && ticks > 0) {
      USE_COOLDOWN_TICKS.put(material, ticks);
    }
  }

  private UseCooldowns() {}

  public static int getUseCooldownTicks(Material material) {
    if (material == null) return -1;
    return USE_COOLDOWN_TICKS.getOrDefault(material, -1);
  }

  public static void applyUseCooldownIfKnown(Player player, Material material) {
    if (player == null || material == null) return;
    int ticks = getUseCooldownTicks(material);
    if (ticks <= 0) return;
    player.setCooldown(material, ticks);
  }
}

