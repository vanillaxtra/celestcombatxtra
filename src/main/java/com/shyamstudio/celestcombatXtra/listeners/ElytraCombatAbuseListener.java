package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.configs.RestrictionConfigPaths;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.api.events.CombatEndEvent;
import com.shyamstudio.celestcombatXtra.api.events.CombatEvent;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.cooldown.UseCooldowns;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elytra combat rules live under the top-level {@code elytra:} config section.
 * <p>
 * Blocks starting glide and firework boosts while in combat (when enabled). Optional
 * {@code abuse_prevention} can unequip the elytra after repeated tries; if inventory is
 * full, either temporarily max durability ({@code TEMP_BREAK}) or {@code DROP} at feet.
 */
public final class ElytraCombatAbuseListener implements Listener {

  private final CelestCombatPro plugin;
  private final CombatManager combatManager;

  private final Map<UUID, Integer> strikes = new ConcurrentHashMap<>();
  private final Map<UUID, Scheduler.Task> pendingRestoreTasks = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> pendingSavedDamage = new ConcurrentHashMap<>();

  public ElytraCombatAbuseListener(CelestCombatPro plugin, CombatManager combatManager) {
    this.plugin = plugin;
    this.combatManager = combatManager;
  }

  /** True if the new {@code elytra:} block exists in config (saved default or user-added). */
  private boolean usesNewElytraConfig() {
    return plugin.getConfig().get("elytra") != null;
  }

  private boolean isElytraModuleEnabled() {
    if (usesNewElytraConfig()) {
      return plugin.getConfig().getBoolean("elytra.enabled", true);
    }
    // Legacy: combat.item_restrictions.elytra_abuse.enabled
    return plugin.getConfig().getBoolean("combat.item_restrictions.elytra_abuse.enabled", true);
  }

  private boolean isAbusePreventionEnabled() {
    if (usesNewElytraConfig()) {
      return plugin.getConfig().getBoolean("elytra.abuse_prevention.enabled", true);
    }
    return plugin.getConfig().getBoolean("combat.item_restrictions.elytra_abuse.enabled", true);
  }

  private boolean requireElytraInDisabledList() {
    if (usesNewElytraConfig()) {
      return plugin.getConfig().getBoolean("elytra.require_elytra_in_disabled_list", true);
    }
    return true;
  }

  private int strikesBeforeAction() {
    if (usesNewElytraConfig()) {
      return Math.max(1, plugin.getConfig().getInt("elytra.abuse_prevention.strikes_before_action", 2));
    }
    return Math.max(1, plugin.getConfig().getInt("combat.item_restrictions.elytra_abuse.strikes_before_action", 2));
  }

  private int tempBreakSeconds() {
    if (usesNewElytraConfig()) {
      return plugin.getConfig().getInt("elytra.abuse_prevention.temp_break_duration_seconds", 30);
    }
    return plugin.getConfig().getInt("combat.item_restrictions.elytra_abuse.temp_break_duration_seconds", 30);
  }

  private FullInventoryAction fullInventoryAction() {
    if (usesNewElytraConfig()) {
      String raw = plugin.getConfig().getString("elytra.abuse_prevention.full_inventory_action", "TEMP_BREAK");
      if (raw != null && raw.trim().equalsIgnoreCase("DROP")) {
        return FullInventoryAction.DROP;
      }
    }
    return FullInventoryAction.TEMP_BREAK;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCombatEnter(CombatEvent event) {
    if (!event.wasAlreadyInCombat()) {
      strikes.remove(event.getPlayer().getUniqueId());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCombatEnd(CombatEndEvent event) {
    strikes.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    strikes.remove(id);
    Scheduler.Task t = pendingRestoreTasks.remove(id);
    if (t != null) {
      t.cancel();
    }
    restoreElytraDurability(event.getPlayer(), id);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onToggleGlide(EntityToggleGlideEvent event) {
    if (!isFeatureEnabled()) return;
    if (!(event.getEntity() instanceof Player player)) return;
    if (!event.isGliding()) return;
    if (!combatManager.isInCombat(player)) return;
    if (!playerWearsElytra(player)) return;

    event.setCancelled(true);
    UseCooldowns.applyUseCooldownIfKnown(player, Material.ELYTRA);
    sendBlockedActionBar(player);
    handleStrike(player);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onFireworkBoost(PlayerInteractEvent event) {
    if (!isFeatureEnabled()) return;
    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

    Player player = event.getPlayer();
    if (!combatManager.isInCombat(player)) return;

    ItemStack item = event.getItem();
    if (item == null || item.getType() != Material.FIREWORK_ROCKET) return;
    if (!playerWearsElytra(player)) return;

    event.setCancelled(true);
    UseCooldowns.applyUseCooldownIfKnown(player, Material.FIREWORK_ROCKET);
    sendBlockedActionBar(player);
    handleStrike(player);
  }

  private String itemRestrictionsRoot() {
    return RestrictionConfigPaths.itemRestrictionsRoot(plugin.getConfig());
  }

  private boolean isFeatureEnabled() {
    String ir = itemRestrictionsRoot();
    if (!plugin.getConfig().getBoolean(ir + ".enabled", true)) return false;
    if (!plugin.getConfig().getBoolean(ir + ".disabled_items.enabled", true)) return false;
    return isElytraModuleEnabled();
  }

  private void handleStrike(Player player) {
    if (!isAbusePreventionEnabled()) return;

    UUID id = player.getUniqueId();
    if (!combatManager.isInCombat(player)) {
      strikes.remove(id);
      return;
    }

    int max = strikesBeforeAction();
    int n = strikes.merge(id, 1, Integer::sum);
    if (n < max) {
      return;
    }

    strikes.remove(id);
    applyElytraPenalty(player);
  }

  private void applyElytraPenalty(Player player) {
    PlayerInventory inv = player.getInventory();
    ItemStack chest = inv.getChestplate();
    if (chest == null || chest.getType() != Material.ELYTRA) return;

    if (tryMoveElytraToInventory(player, inv, chest)) {
      player.setGliding(false);
      sendPenaltyMessage(player, "elytra_removed_combat_abuse", Map.of());
      return;
    }

    player.setGliding(false);

    if (fullInventoryAction() == FullInventoryAction.DROP) {
      cancelPendingRestore(player.getUniqueId());
      ItemStack drop = chest.clone();
      inv.setChestplate(null);
      player.getWorld().dropItemNaturally(player.getLocation(), drop);
      player.updateInventory();
      sendPenaltyMessage(player, "elytra_dropped_combat_abuse", Map.of());
      return;
    }

    int savedDamage = readDamage(chest);
    int maxDmg = Material.ELYTRA.getMaxDurability();
    if (!setDamage(chest, maxDmg)) {
      return;
    }
    inv.setChestplate(chest);
    player.updateInventory();

    cancelPendingRestore(player.getUniqueId());
    pendingSavedDamage.put(player.getUniqueId(), savedDamage);

    int seconds = tempBreakSeconds();
    long delayTicks = Math.max(1L, seconds * 20L);

    Map<String, String> ph = Map.of("seconds", String.valueOf(seconds));
    sendPenaltyMessage(player, "elytra_temp_broken_combat_abuse", ph);

    Scheduler.Task task = Scheduler.runEntityTaskLater(player, () -> {
      UUID uuid = player.getUniqueId();
      Player online = plugin.getServer().getPlayer(uuid);
      if (online != null && online.isOnline()) {
        restoreElytraDurability(online, uuid);
      } else {
        pendingSavedDamage.remove(uuid);
      }
      pendingRestoreTasks.remove(uuid);
    }, delayTicks);

    pendingRestoreTasks.put(player.getUniqueId(), task);
  }

  private boolean tryMoveElytraToInventory(Player player, PlayerInventory inv, ItemStack elytraOnChest) {
    ItemStack clone = elytraOnChest.clone();
    inv.setChestplate(null);

    HashMap<Integer, ItemStack> leftover = inv.addItem(clone);
    if (!leftover.isEmpty()) {
      inv.setChestplate(elytraOnChest);
      return false;
    }
    player.updateInventory();
    return true;
  }

  private void restoreElytraDurability(Player player, UUID uuid) {
    Integer saved = pendingSavedDamage.remove(uuid);
    if (saved == null) return;

    if (player == null || !player.isOnline()) return;

    ItemStack chest = player.getInventory().getChestplate();
    if (chest == null || chest.getType() != Material.ELYTRA) return;

    if (!setDamage(chest, saved)) return;
    player.getInventory().setChestplate(chest);
    player.updateInventory();
  }

  private void cancelPendingRestore(UUID uuid) {
    Scheduler.Task t = pendingRestoreTasks.remove(uuid);
    if (t != null) {
      t.cancel();
    }
    pendingSavedDamage.remove(uuid);
  }

  private static int readDamage(ItemStack stack) {
    ItemMeta meta = stack.getItemMeta();
    if (meta instanceof Damageable d) {
      return d.getDamage();
    }
    return 0;
  }

  private static boolean setDamage(ItemStack stack, int damage) {
    ItemMeta meta = stack.getItemMeta();
    if (!(meta instanceof Damageable d)) {
      return false;
    }
    d.setDamage(Math.max(0, damage));
    stack.setItemMeta(meta);
    return true;
  }

  /** Player wears elytra and passes disabled-list rule when required. */
  private boolean playerWearsElytra(Player player) {
    ItemStack chest = player.getInventory().getChestplate();
    if (chest == null || chest.getType() != Material.ELYTRA) return false;
    if (requireElytraInDisabledList()) {
      return elytraListedInDisabledItems();
    }
    return true;
  }

  private boolean elytraListedInDisabledItems() {
    String ir = itemRestrictionsRoot();
    List<String> disabled = plugin.getConfig().getStringList(ir + ".disabled_items.items");
    if (disabled.isEmpty()) {
      disabled = plugin.getConfig().getStringList(ir + ".disabled_items");
    }
    return disabled.stream().anyMatch(entry -> Material.ELYTRA.name().equalsIgnoreCase(entry));
  }

  private void sendBlockedActionBar(Player player) {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("player", player.getName());
    placeholders.put("item", "Elytra");
    plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
  }

  private void sendPenaltyMessage(Player player, String key, Map<String, String> placeholders) {
    if (player == null) return;
    plugin.getMessageService().sendMessage(player, key, placeholders);
  }

  private void sendRestrictionActionBar(Player player, String key, Map<String, String> placeholders) {
    if (player == null || key == null) return;
    plugin.getMessageService().sendMessage(player, key, placeholders);
  }

  private enum FullInventoryAction {
    TEMP_BREAK,
    DROP
  }
}
