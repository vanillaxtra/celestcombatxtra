package com.shyamstudio.celestcombatXtra.configs;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Resolves {@code windcharge.*} vs legacy {@code windcharge_control.*}.
 */
public final class WindchargeConfigPaths {

  private WindchargeConfigPaths() {}

  public static String root(FileConfiguration config) {
    if (config.get("windcharge") != null) {
      return "windcharge";
    }
    return "windcharge_control";
  }
}
