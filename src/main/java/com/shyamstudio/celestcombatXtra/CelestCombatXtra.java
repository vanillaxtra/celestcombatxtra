package com.shyamstudio.celestcombatXtra;

import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.listeners.GeneralItemCooldownListener;
import com.shyamstudio.celestcombatXtra.listeners.HarmingArrowListener;
import com.shyamstudio.celestcombatXtra.listeners.SpearControlListener;
import com.shyamstudio.celestcombatXtra.listeners.WindChargeListener;

import java.util.Collections;
import java.util.List;

/**
 * New main class for the renamed plugin.
 * This keeps backward-compatible internal package structure.
 */
public class CelestCombatXtra extends CelestCombatPro {
  private ItemCooldownManager itemCooldownManager;
  private WindChargeListener windChargeListener;
  private GeneralItemCooldownListener generalItemCooldownListener;

  @Override
  public void onEnable() {
    // Base plugin registration (existing combat logic, ender pearl, trident, etc.)
    super.onEnable();

    // Phase 1: cooldowns + arrow + block disablers
    itemCooldownManager = new ItemCooldownManager(this);

    // Let combat + pearl/trident countdowns merge wind/general cooldowns into one action bar.
    if (getCombatManager() != null) {
      getCombatManager().setItemCooldownManager(itemCooldownManager);
    }
    if (getEnderPearlListener() != null) {
      getEnderPearlListener().setItemCooldownManager(itemCooldownManager);
    }
    if (getTridentListener() != null) {
      getTridentListener().setItemCooldownManager(itemCooldownManager);
    }

    windChargeListener = new WindChargeListener(this, itemCooldownManager);
    getServer().getPluginManager().registerEvents(windChargeListener, this);

    generalItemCooldownListener = new GeneralItemCooldownListener(this, itemCooldownManager);
    getServer().getPluginManager().registerEvents(generalItemCooldownListener, this);

    getServer().getPluginManager().registerEvents(
        new HarmingArrowListener(this),
        this
    );

    getServer().getPluginManager().registerEvents(
        new SpearControlListener(this, itemCooldownManager),
        this
    );
  }

  /**
   * Re-read wind charge + general item cooldown config (after {@code reloadConfig()}).
   *
   * @return reserved materials skipped in {@code item_restrictions.cooldowned_items} (may be empty)
   */
  public List<String> reloadPhase1Listeners() {
    if (windChargeListener != null) {
      windChargeListener.reloadFromConfig();
    }
    if (generalItemCooldownListener != null) {
      return generalItemCooldownListener.reloadFromConfig();
    }
    return Collections.emptyList();
  }

  @Override
  public void onDisable() {
    if (itemCooldownManager != null) {
      itemCooldownManager.shutdown();
      itemCooldownManager = null;
    }

    super.onDisable();
  }
}
