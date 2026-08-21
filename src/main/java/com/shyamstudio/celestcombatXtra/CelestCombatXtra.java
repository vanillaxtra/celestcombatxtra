package com.shyamstudio.celestcombatXtra;

import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.highlight.PacketEventsBootstrap;
import com.shyamstudio.celestcombatXtra.listeners.GeneralItemCooldownListener;
import com.shyamstudio.celestcombatXtra.listeners.HarmingArrowListener;
import com.shyamstudio.celestcombatXtra.listeners.ItemLimiterListener;
import com.shyamstudio.celestcombatXtra.listeners.SpearControlListener;
import com.shyamstudio.celestcombatXtra.listeners.WindChargeListener;

import org.bukkit.event.Listener;

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
  private ItemLimiterListener itemLimiterListener;
  private SpearControlListener spearControlListener;

  @Override
  public void onEnable() {
    // PacketEvents is only required for the PVP status highlight (glow) feature,
    // and must be installed by the server owner as a separate plugin (soft-depend,
    // not shaded). If it's absent, CelestCombatPro simply skips constructing the
    // highlight manager - the rest of the plugin is unaffected.
    PacketEventsBootstrap.detect(this);

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

    itemLimiterListener = new ItemLimiterListener(this);
    getServer().getPluginManager().registerEvents(itemLimiterListener, this);

    spearControlListener = new SpearControlListener(this, itemCooldownManager);
    getServer().getPluginManager().registerEvents(spearControlListener, this);
    registerSpearLungePaperListener(spearControlListener);

    // PlaceholderAPI
    if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
      new com.shyamstudio.celestcombatXtra.placeholders.CelestCombatPlaceholders(this).register();
      getLogger().info("PlaceholderAPI expansion registered!");
    }
  }

  /** Exposes ItemCooldownManager for PlaceholderAPI wind charge placeholders. */
  public ItemCooldownManager getItemCooldownManager() {
    return itemCooldownManager;
  }

  private void registerSpearLungePaperListener(SpearControlListener spearControl) {
    try {
      Class.forName("io.papermc.paper.event.entity.EntityLungeEvent");
      Class<?> listenerClass = Class.forName(
          "com.shyamstudio.celestcombatXtra.listeners.SpearLungePaperListener");
      Object listener = listenerClass.getConstructor(SpearControlListener.class).newInstance(spearControl);
      getServer().getPluginManager().registerEvents((Listener) listener, this);
    } catch (ReflectiveOperationException ignored) {
      // 1.21 servers without EntityLungeEvent
    }
  }

  /** Exposes ItemLimiterListener for bundle/restriction checks. */
  public ItemLimiterListener getItemLimiterListener() {
    return itemLimiterListener;
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
    List<String> skipped = Collections.emptyList();
    if (generalItemCooldownListener != null) {
      skipped = generalItemCooldownListener.reloadFromConfig();
    }
    // must run even when general cooldown returns early
    if (itemLimiterListener != null) {
      itemLimiterListener.reloadLimits();
    }
    return skipped;
  }

  @Override
  public void onDisable() {
    if (spearControlListener != null) {
      spearControlListener.shutdown();
      spearControlListener = null;
    }
    if (itemLimiterListener != null) {
      itemLimiterListener.shutdown();
      itemLimiterListener = null;
    }
    if (itemCooldownManager != null) {
      itemCooldownManager.shutdown();
      itemCooldownManager = null;
    }

    super.onDisable();
  }
}
