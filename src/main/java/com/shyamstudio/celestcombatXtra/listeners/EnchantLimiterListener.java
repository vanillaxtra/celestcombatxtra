package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Caps or removes overpowered enchantments based on {@code enchant_limiter.limits}.
 * <p>
 * {@code violation_action}: DELETE (remove item), REVERT (clamp levels), NONE (no change).
 */
public final class EnchantLimiterListener implements Listener {

  private final CelestCombatPro plugin;

  public EnchantLimiterListener(CelestCombatPro plugin) {
    this.plugin = plugin;
  }

  private boolean enabled() {
    return plugin.getConfig().getBoolean("enchant_limiter.enabled", false);
  }

  private boolean enabledInWorld(String worldName) {
    if (!enabled()) return false;
    var sec = plugin.getConfig().getConfigurationSection("enchant_limiter.worlds");
    if (sec == null || sec.getKeys(false).isEmpty()) return true;
    return plugin.getConfig().getBoolean("enchant_limiter.worlds." + worldName, true);
  }

  private String bypassPerm() {
    return plugin.getConfig().getString("enchant_limiter.bypass_permission", "celestcombatxtra.bypass.enchant_limit");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (!enabledInWorld(player.getWorld().getName())) return;
    Scheduler.runEntityTaskLater(player, () -> scanPlayer(player), 2L);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    Scheduler.runEntityTaskLater(player, () -> scanPlayer(player), 1L);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    Scheduler.runEntityTaskLater(player, () -> scanPlayer(player), 1L);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPickup(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    Scheduler.runEntityTaskLater(player, () -> scanPlayer(player), 1L);
  }

  public void scanPlayer(Player player) {
    if (player == null || !player.isOnline()) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (player.hasPermission(bypassPerm())) return;

    Map<Enchantment, Integer> limits = loadLimits();
    if (limits.isEmpty()) return;

    String actionRaw = plugin.getConfig().getString("enchant_limiter.violation_action", "REVERT").toUpperCase(Locale.ROOT);
    ViolationAction action = switch (actionRaw) {
      case "DELETE" -> ViolationAction.DELETE;
      case "NONE", "IGNORE" -> ViolationAction.NONE;
      default -> ViolationAction.REVERT;
    };

    PlayerInventory inv = player.getInventory();
    boolean anyDelete = false;
    boolean anyRevert = false;

    for (int i = 0; i < inv.getSize(); i++) {
      int slot = i;
      boolean[] out = new boolean[2];
      fixSlot(() -> inv.getItem(slot), s -> inv.setItem(slot, s), limits, action, out);
      anyDelete |= out[0];
      anyRevert |= out[1];
    }

    boolean[] o1 = new boolean[2];
    fixSlot(inv::getItemInOffHand, inv::setItemInOffHand, limits, action, o1);
    anyDelete |= o1[0];
    anyRevert |= o1[1];

    boolean[] h = new boolean[2];
    fixSlot(inv::getHelmet, inv::setHelmet, limits, action, h);
    anyDelete |= h[0];
    anyRevert |= h[1];

    boolean[] c = new boolean[2];
    fixSlot(inv::getChestplate, inv::setChestplate, limits, action, c);
    anyDelete |= c[0];
    anyRevert |= c[1];

    boolean[] l = new boolean[2];
    fixSlot(inv::getLeggings, inv::setLeggings, limits, action, l);
    anyDelete |= l[0];
    anyRevert |= l[1];

    boolean[] b = new boolean[2];
    fixSlot(inv::getBoots, inv::setBoots, limits, action, b);
    anyDelete |= b[0];
    anyRevert |= b[1];

    if (anyDelete || anyRevert) {
      player.updateInventory();
      if (anyDelete) {
        plugin.getMessageService().sendMessage(player, "enchant_limiter_item_removed");
      }
      if (anyRevert) {
        plugin.getMessageService().sendMessage(player, "enchant_limiter_reverted");
      }
    }
  }

  /** out[0] = deleted, out[1] = reverted */
  private void fixSlot(Supplier<ItemStack> get, Consumer<ItemStack> set,
      Map<Enchantment, Integer> limits, ViolationAction action, boolean[] out) {
    ItemStack cur = get.get();
    ItemStack next = resolveStack(cur, limits, action);
    if (next == null) {
      if (cur != null && !cur.getType().isAir()) {
        set.accept(null);
        out[0] = true;
      }
    } else if (next != cur) {
      set.accept(next);
      out[1] = true;
    }
  }

  /**
   * @return null means delete slot; same reference means unchanged; different reference means reverted clone
   */
  private ItemStack resolveStack(ItemStack stack, Map<Enchantment, Integer> limits, ViolationAction action) {
    if (stack == null || stack.getType().isAir()) return stack;
    if (!exceedsAnyLimit(stack, limits)) return stack;
    if (action == ViolationAction.NONE) return stack;
    if (action == ViolationAction.DELETE) return null;
    return applyRevertClone(stack, limits);
  }

  private ItemStack applyRevertClone(ItemStack stack, Map<Enchantment, Integer> limits) {
    ItemStack copy = stack.clone();
    ItemMeta meta = copy.getItemMeta();
    if (meta == null) return stack;

    if (meta.hasEnchants()) {
      for (Map.Entry<Enchantment, Integer> e : new HashMap<>(meta.getEnchants()).entrySet()) {
        int cap = limits.getOrDefault(e.getKey(), Integer.MAX_VALUE);
        if (e.getValue() > cap) {
          meta.removeEnchant(e.getKey());
          if (cap > 0) {
            meta.addEnchant(e.getKey(), cap, true);
          }
        }
      }
    }
    if (meta instanceof EnchantmentStorageMeta sm && sm.hasStoredEnchants()) {
      for (Map.Entry<Enchantment, Integer> e : new HashMap<>(sm.getStoredEnchants()).entrySet()) {
        int cap = limits.getOrDefault(e.getKey(), Integer.MAX_VALUE);
        if (e.getValue() > cap) {
          sm.removeStoredEnchant(e.getKey());
          if (cap > 0) {
            sm.addStoredEnchant(e.getKey(), cap, true);
          }
        }
      }
    }
    copy.setItemMeta(meta);
    return copy;
  }

  private boolean exceedsAnyLimit(ItemStack stack, Map<Enchantment, Integer> limits) {
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) return false;
    if (meta.hasEnchants()) {
      for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
        int cap = limits.getOrDefault(e.getKey(), Integer.MAX_VALUE);
        if (e.getValue() > cap) return true;
      }
    }
    if (meta instanceof EnchantmentStorageMeta sm && sm.hasStoredEnchants()) {
      for (Map.Entry<Enchantment, Integer> e : sm.getStoredEnchants().entrySet()) {
        int cap = limits.getOrDefault(e.getKey(), Integer.MAX_VALUE);
        if (e.getValue() > cap) return true;
      }
    }
    return false;
  }

  private Map<Enchantment, Integer> loadLimits() {
    Map<Enchantment, Integer> map = new HashMap<>();
    var sec = plugin.getConfig().getConfigurationSection("enchant_limiter.limits");
    if (sec == null) return map;
    for (String key : sec.getKeys(false)) {
      Enchantment enc = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
      if (enc == null) {
        continue;
      }
      map.put(enc, Math.max(0, sec.getInt(key)));
    }
    return map;
  }

  private enum ViolationAction {
    DELETE, REVERT, NONE
  }
}
