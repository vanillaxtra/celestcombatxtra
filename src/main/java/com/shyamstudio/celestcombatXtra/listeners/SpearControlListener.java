package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import io.papermc.paper.event.player.PlayerArmSwingEvent;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager.CooldownKey;
import com.shyamstudio.celestcombatXtra.util.SpearMaterials;

import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spear (1.21+) lunge cooldown and optional damage disable.
 */
public final class SpearControlListener implements Listener {

  private static final String BYPASS = "celestcombatxtra.bypass.spear_control";
  private static final double LUNGE_VELOCITY_MIN = 0.3D;

  private final CelestCombatPro plugin;
  private final ItemCooldownManager cooldownManager;
  private final Set<UUID> blockNextVelocity = ConcurrentHashMap.newKeySet();

  public SpearControlListener(CelestCombatPro plugin, ItemCooldownManager cooldownManager) {
    this.plugin = plugin;
    this.cooldownManager = cooldownManager;
  }

  private static CooldownKey lungeKey(Material material) {
    return new CooldownKey(material, ItemCooldownManager.LUNGE_META_KEY);
  }

  private boolean master() {
    return plugin.getConfig().getBoolean("spear_control.enabled", false);
  }

  private boolean appliesTo(Player player) {
    if (player == null || player.hasPermission(BYPASS)) return false;
    if (plugin.getConfig().getBoolean("spear_control.in_combat_only", false)
        && !plugin.getCombatManager().isInCombat(player)) {
      return false;
    }
    return true;
  }

  /**
   * 26.x backup cancel hook. returns true when the lunge should be cancelled.
   */
  public boolean handleLungeAttempt(Player player, Material spearMaterial) {
    return shouldBlockLunge(player, spearMaterial);
  }

  private boolean shouldBlockLunge(Player player, Material spearMaterial) {
    if (player == null || spearMaterial == null || !SpearMaterials.isSpear(spearMaterial)) return false;

    if (plugin.getConfig().getBoolean("spear_control.disable_spears", false)) {
      if (!appliesTo(player)) return false;
      notifySpearDisabled(player);
      return true;
    }

    if (!master()) return false;
    if (!plugin.getConfig().getBoolean("spear_control.lunge_cooldown.enabled", true)) return false;
    if (!appliesTo(player)) return false;

    CooldownKey key = lungeKey(spearMaterial);
    if (cooldownManager.isGeneralItemOnCooldown(player, key)) {
      refreshLungeOverlay(player, spearMaterial, key);
      return true;
    }
    return false;
  }

  /** arm swing lunge detect (same approach as lunge-cooldown reference plugin) */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSpearLungeAnimation(PlayerAnimationEvent event) {
    if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

    Player player = event.getPlayer();
    ItemStack main = player.getInventory().getItemInMainHand();
    if (!isLungeAttempt(main)) return;

    Material spearMaterial = main.getType();
    CooldownKey key = lungeKey(spearMaterial);

    if (plugin.getConfig().getBoolean("spear_control.disable_spears", false)) {
      if (!appliesTo(player)) return;
      event.setCancelled(true);
      blockNextVelocity.add(player.getUniqueId());
      notifySpearDisabled(player);
      return;
    }

    if (!master()) return;
    if (!plugin.getConfig().getBoolean("spear_control.lunge_cooldown.enabled", true)) return;
    if (!appliesTo(player)) return;

    long cooldownMs = getLungeCooldownMs();
    if (cooldownMs <= 0) return;

    if (cooldownManager.isGeneralItemOnCooldown(player, key)) {
      event.setCancelled(true);
      blockNextVelocity.add(player.getUniqueId());
      refreshLungeOverlay(player, spearMaterial, key);
      return;
    }

    cooldownManager.startGeneralCooldown(player, key, cooldownMs);
  }

  /** stop dash velocity when lunge was blocked on cooldown */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onLungeVelocity(PlayerVelocityEvent event) {
    Player player = event.getPlayer();
    if (!blockNextVelocity.remove(player.getUniqueId())) return;

    Vector velocity = event.getVelocity();
    double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
    if (horizontal <= LUNGE_VELOCITY_MIN) return;

    Scheduler.runTaskLater(() -> {
      if (player.isOnline()) {
        player.setVelocity(new Vector(0, 0, 0));
      }
    }, 1L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    blockNextVelocity.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSpearInteract(PlayerInteractEvent event) {
    if (SpearMaterials.all().isEmpty()) return;
    if (event.getHand() != EquipmentSlot.HAND) return;
    if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

    Player player = event.getPlayer();
    ItemStack item = event.getItem();
    if (item == null || !SpearMaterials.isSpear(item.getType())) return;

    if (!plugin.getConfig().getBoolean("spear_control.disable_spears", false)) return;
    if (!appliesTo(player)) return;

    event.setCancelled(true);
    event.setUseItemInHand(Event.Result.DENY);
    if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
      event.setUseInteractedBlock(Event.Result.DENY);
    }
    notifySpearDisabled(player);
  }

  private long getLungeCooldownMs() {
    String durStr = plugin.getConfig().getString("spear_control.lunge_cooldown.duration", "1s");
    long durationTicks = plugin.getTimeFormatter().parseTimeToTicks(durStr, 20L);
    return Math.max(0L, durationTicks * 50L);
  }

  private void refreshLungeOverlay(Player player, Material spearMaterial, CooldownKey key) {
    cooldownManager.refreshGeneralItemCooldownOverlay(player, key);
    int rt = cooldownManager.getRemainingGeneralItemCooldownTicks(player, key);
    if (rt > 0) {
      player.setCooldown(spearMaterial, rt);
    }
  }

  public void shutdown() {
    blockNextVelocity.clear();
  }

  /** cancels spear arm swing when disable_spears is true */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSpearArmSwing(PlayerArmSwingEvent event) {
    if (SpearMaterials.all().isEmpty()) return;
    if (!plugin.getConfig().getBoolean("spear_control.disable_spears", false)) return;

    Player player = event.getPlayer();
    ItemStack main = player.getInventory().getItemInMainHand();
    ItemStack off = player.getInventory().getItemInOffHand();
    boolean hasSpear = (main != null && SpearMaterials.isSpear(main.getType()))
        || (off != null && SpearMaterials.isSpear(off.getType()));
    if (!hasSpear) return;
    if (!appliesTo(player)) return;

    event.setCancelled(true);
    notifySpearDisabled(player);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSpearDamage(EntityDamageByEntityEvent event) {
    if (SpearMaterials.all().isEmpty()) return;
    boolean disableDamage = plugin.getConfig().getBoolean("spear_control.disable_damage", false);
    boolean disableSpears = plugin.getConfig().getBoolean("spear_control.disable_spears", false);
    if (!disableSpears && !disableDamage) return;
    if (!isSpearDamage(event)) return;

    Player attacker = resolveAttacker(event);
    if (attacker != null && !appliesTo(attacker)) return;

    event.setCancelled(true);
  }

  private static boolean isLungeAttempt(ItemStack item) {
    if (item == null || item.getType().isAir()) return false;
    if (SpearMaterials.isSpear(item.getType())) return true;
    return getLungeLevel(item) > 0;
  }

  private static int getLungeLevel(ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
    for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
      Enchantment enchant = entry.getKey();
      if (enchant == null) continue;
      if (enchant.getKey().getKey().toLowerCase(Locale.ROOT).contains("lunge")) {
        return entry.getValue();
      }
    }
    return 0;
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
      return SpearMaterials.isSpear(hand.getType());
    }
    if (event.getDamager() instanceof Projectile proj) {
      return proj.getType().name().contains("SPEAR");
    }
    return false;
  }

  private void notifySpearDisabled(Player player) {
    if (player == null) return;
    Component bar = plugin.getLanguageManager().getActionBar("spear_disabled", Map.of());
    if (bar != null) {
      plugin.sendActionBar(player, bar);
    } else {
      plugin.getMessageService().sendMessage(player, "spear_disabled", Map.of());
    }
  }
}
