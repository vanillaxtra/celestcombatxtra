package com.shyamstudio.celestcombatXtra.configs;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Resolves config paths for item restrictions and regearing.
 * <p>
 * New layout uses top-level {@code item_restrictions} and {@code regearing}.
 * Legacy nested paths under {@code combat.*} are used when the new keys are absent.
 */
public final class RestrictionConfigPaths {

  private RestrictionConfigPaths() {}

  public static String itemRestrictionsRoot(FileConfiguration config) {
    if (config.get("item_restrictions") != null) {
      return "item_restrictions";
    }
    return "combat.item_restrictions";
  }

  public static String regearingRoot(FileConfiguration config) {
    if (config.get("regearing") != null) {
      return "regearing";
    }
    return "combat.regearing";
  }

  /**
   * Map list path for cooldowned item entries (new, legacy combat.*, or general_item_cooldowns).
   */
  public static String cooldownedEntriesPath(FileConfiguration config) {
    if (config.isList("item_restrictions.cooldowned_items.entries")) {
      return "item_restrictions.cooldowned_items.entries";
    }
    if (config.isList("combat.item_restrictions.cooldowned_items.entries")) {
      return "combat.item_restrictions.cooldowned_items.entries";
    }
    return "general_item_cooldowns.entries";
  }
}
