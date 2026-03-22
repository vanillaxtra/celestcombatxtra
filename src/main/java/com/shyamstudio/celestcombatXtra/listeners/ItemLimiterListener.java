package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.BundleMeta;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player item limiter (GeneralPVP-style): periodic scan every 5 ticks + event-based prevention.
 * Supports partial pickup when player has room for some but not all. Counts bundle contents.
 */
public final class ItemLimiterListener implements Listener {

  private final CelestCombatPro plugin;
  private volatile Map<Material, Integer> limits = new HashMap<>();
  private Scheduler.Task periodicTask;
  private final java.util.Set<UUID> swapGracePlayers = ConcurrentHashMap.newKeySet();
  private int scanBatchIndex = 0;
  private final Map<UUID, Map<Material, Integer>> messageCountByPlayer = new ConcurrentHashMap<>();
  private final Map<UUID, Map<Material, Long>> lastDenyTimeByPlayer = new ConcurrentHashMap<>();

  public ItemLimiterListener(CelestCombatPro plugin) {
    this.plugin = plugin;
    reloadLimits();
    startPeriodicScan();
  }

  public void shutdown() {
    if (periodicTask != null) {
      periodicTask.cancel();
      periodicTask = null;
    }
  }

  /** Call after plugin.reloadConfig() to refresh cached limits and scan settings. */
  public void reloadLimits() {
    Map<Material, Integer> next = new HashMap<>();
    var sec = plugin.getConfig().getConfigurationSection("item_limiter.limits");
    if (sec != null) {
      for (String key : sec.getKeys(false)) {
        try {
          Material m = Material.valueOf(key.toUpperCase(Locale.ROOT));
          next.put(m, Math.max(0, sec.getInt(key)));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    limits = next;
    startPeriodicScan();
  }

  private void startPeriodicScan() {
    if (periodicTask != null) periodicTask.cancel();
    long interval = Math.max(1, plugin.getConfig().getLong("item_limiter.scan_interval_ticks", 10L));
    int perTick = Math.max(1, plugin.getConfig().getInt("item_limiter.players_per_tick", 8));
    periodicTask = Scheduler.runTaskTimer(() -> {
      if (!enabled()) return;
      List<? extends Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
      if (players.isEmpty()) return;
      int batchSize = Math.min(perTick, players.size());
      int start = Math.abs(scanBatchIndex % players.size());
      for (int i = 0; i < batchSize; i++) {
        int idx = (start + i) % players.size();
        Player p = players.get(idx);
        if (p.isOnline() && !swapGracePlayers.contains(p.getUniqueId()))
          scanAndRemoveExcess(p);
      }
      scanBatchIndex += batchSize;
    }, interval, interval);
  }

  private boolean enabled() {
    return plugin.getConfig().getBoolean("item_limiter.enabled", false);
  }

  private boolean enabledInWorld(String worldName) {
    if (!enabled()) return false;
    var sec = plugin.getConfig().getConfigurationSection("item_limiter.worlds");
    if (sec == null || sec.getKeys(false).isEmpty()) return true;
    return plugin.getConfig().getBoolean("item_limiter.worlds." + worldName, true);
  }

  private String bypassPerm() {
    return plugin.getConfig().getString("item_limiter.bypass_permission", "celestcombatxtra.bypass.item_limit");
  }

  private boolean hasBypass(Player player) {
    if (player == null) return false;
    if (!plugin.getConfig().getBoolean("item_limiter.op_bypasses", false) && player.isOp()) return false;
    return player.hasPermission(bypassPerm());
  }

  private boolean dropExcess() {
    return plugin.getConfig().getBoolean("item_limiter.drop_excess", true);
  }

  private void deny(Player player, Material material, int limit) {
    long now = System.currentTimeMillis();
    long cooldownMs = plugin.getConfig().getLong("item_limiter.deny_message_cooldown_ticks", 100L) * 50L;
    Map<Material, Long> last = lastDenyTimeByPlayer.computeIfAbsent(player.getUniqueId(), u -> new ConcurrentHashMap<>());
    Long lastTime = last.get(material);
    if (lastTime != null && (now - lastTime) < cooldownMs) return;
    last.put(material, now);
    Map<String, String> ph = new HashMap<>();
    ph.put("limit", String.valueOf(limit));
    ph.put("material", formatMaterial(material));
    plugin.getMessageService().sendMessage(player, "item_limiter_denied", ph);
  }

  private static String formatMaterial(Material m) {
    return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static boolean isBundleView(InventoryView view) {
    if (view == null || view.getTopInventory() == null) return false;
    String title = view.getTitle();
    if (title != null && title.toLowerCase(Locale.ROOT).contains("bundle")) return true;
    Object holder = view.getTopInventory().getHolder();
    return holder != null && holder.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("bundle");
  }

  /**
   * Compute how many result items would be crafted on shift-click (max craftable from current matrix).
   * Returns 0 if recipe is null or not computable.
   */
  private int computeMaxCraftable(CraftingInventory inv) {
    Recipe recipe = inv.getRecipe();
    if (recipe == null) return 0;
    ItemStack result = recipe.getResult();
    if (result == null || result.getType().isAir()) return 0;
    int perCraft = result.getAmount();
    if (perCraft <= 0) return 0;
    ItemStack[] matrix = inv.getMatrix();
    if (matrix == null) return 0;
    int maxBatches;
    if (recipe instanceof ShapelessRecipe sr) {
      List<RecipeChoice> choices = sr.getChoiceList();
      if (choices == null || choices.isEmpty()) return perCraft;
      Map<RecipeChoice, Integer> neededPerBatch = new HashMap<>();
      for (RecipeChoice c : choices) {
        neededPerBatch.merge(c, 1, Integer::sum);
      }
      maxBatches = Integer.MAX_VALUE;
      for (Map.Entry<RecipeChoice, Integer> e : neededPerBatch.entrySet()) {
        int need = e.getValue();
        int count = 0;
        for (ItemStack slot : matrix) {
          if (slot != null && !slot.getType().isAir() && e.getKey().test(slot)) count += slot.getAmount();
        }
        if (count < need) return 0;
        maxBatches = Math.min(maxBatches, count / need);
      }
      if (maxBatches == Integer.MAX_VALUE) return perCraft;
    } else if (recipe instanceof ShapedRecipe shaped) {
      Map<Character, RecipeChoice> map = shaped.getChoiceMap();
      if (map == null) return perCraft;
      Map<RecipeChoice, Integer> neededPerBatch = new HashMap<>();
      for (String row : shaped.getShape()) {
        for (char c : row.toCharArray()) {
          RecipeChoice choice = map.get(c);
          if (choice != null) neededPerBatch.merge(choice, 1, Integer::sum);
        }
      }
      if (neededPerBatch.isEmpty()) return perCraft;
      maxBatches = Integer.MAX_VALUE;
      for (Map.Entry<RecipeChoice, Integer> e : neededPerBatch.entrySet()) {
        int need = e.getValue();
        int count = 0;
        for (ItemStack slot : matrix) {
          if (slot != null && !slot.getType().isAir() && e.getKey().test(slot)) count += slot.getAmount();
        }
        if (count < need) return 0;
        maxBatches = Math.min(maxBatches, count / need);
      }
      if (maxBatches == Integer.MAX_VALUE) return perCraft;
    } else {
      return perCraft;
    }
    return Math.min(maxBatches * perCraft, result.getType().getMaxStackSize() * 64);
  }

  private int countMaterial(PlayerInventory inv, Material m) {
    if (m == null || m.isAir()) return 0;
    int sum = 0;
    for (int i = 0; i < inv.getSize(); i++) {
      ItemStack s = inv.getItem(i);
      if (s == null) continue;
      if (s.getType() == m) sum += s.getAmount();
      else if (s.getType() == Material.BUNDLE && s.getItemMeta() instanceof BundleMeta bm && bm.hasItems())
        sum += countMaterialInList(bm.getItems(), m);
    }
    return sum;
  }

  /** Single pass: count all limited materials. Returns map of material -> count (only for materials in limits). */
  private Map<Material, Integer> countAllLimited(PlayerInventory inv, Map<Material, Integer> lims) {
    Map<Material, Integer> counts = new HashMap<>();
    for (int i = 0; i < inv.getSize(); i++) {
      ItemStack s = inv.getItem(i);
      if (s == null) continue;
      Material mt = s.getType();
      if (lims.containsKey(mt)) counts.merge(mt, s.getAmount(), Integer::sum);
      else if (mt == Material.BUNDLE && s.getItemMeta() instanceof BundleMeta bm && bm.hasItems()) {
        for (ItemStack stack : bm.getItems()) {
          if (stack != null && lims.containsKey(stack.getType()))
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
      }
    }
    return counts;
  }

  private int countMaterialInList(List<ItemStack> items, Material m) {
    if (items == null) return 0;
    int sum = 0;
    for (ItemStack s : items) {
      if (s != null && s.getType() == m) sum += s.getAmount();
    }
    return sum;
  }

  /** How many of this material can the player pick up? -1 = not limited, 0 = none, >0 = that many. */
  private int getPickupAmount(Player player, Material m, int stackAmount) {
    if (m == null || m.isAir()) return -1;
    Integer limit = limits.get(m);
    if (limit == null) return -1;
    int current = countMaterial(player.getInventory(), m);
    if (current >= limit) return 0;
    int canTake = limit - current;
    return Math.min(stackAmount, canTake);
  }

  /** How many of this material can the player add (from any source)? -1 = not limited, 0 = none, >0 = that many. */
  private int getCanAdd(Player player, Material m, int available) {
    if (m == null || m.isAir()) return -1;
    Integer limit = limits.get(m);
    if (limit == null) return -1;
    int current = countMaterial(player.getInventory(), m);
    if (current >= limit) return 0;
    return Math.min(available, limit - current);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
    if (!enabledInWorld(event.getPlayer().getWorld().getName())) return;
    Player player = event.getPlayer();
    if (hasBypass(player)) return;

    Item item = event.getItem();
    ItemStack stack = item.getItemStack();
    if (stack == null || stack.getType().isAir()) return;

    Material m = stack.getType();
    int amount = stack.getAmount();
    int canTake = getPickupAmount(player, m, amount);

    if (canTake == -1) return;

    if (canTake <= 0) {
      event.setCancelled(true);
      deny(player, m, limits.get(m));
      return;
    }

    if (canTake < amount) {
      event.setCancelled(true);
      ItemStack toGet = stack.clone();
      toGet.setAmount(canTake);
      ItemStack toLeave = stack.clone();
      toLeave.setAmount(amount - canTake);
      item.setItemStack(toLeave);
      HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(toGet);
      for (ItemStack o : overflow.values()) {
        if (o != null && !o.getType().isAir()) {
          player.getWorld().dropItemNaturally(player.getLocation(), o);
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPickupFallback(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    ItemStack stack = event.getItem().getItemStack();
    Material m = stack == null ? null : stack.getType();
    if (m == null || m.isAir() || !limits.containsKey(m)) return;

    int canTake = getPickupAmount(player, m, stack.getAmount());
    if (canTake != -1 && canTake <= 0) {
      event.setCancelled(true);
      deny(player, m, limits.get(m));
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onHopperMove(InventoryMoveItemEvent event) {
    if (!(event.getDestination().getHolder() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    ItemStack stack = event.getItem();
    if (stack == null || stack.getType().isAir()) return;
    Material m = stack.getType();
    if (!limits.containsKey(m)) return;

    int current = countMaterial(player.getInventory(), m);
    Integer limit = limits.get(m);
    if (current + stack.getAmount() > limit) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    UUID uid = event.getPlayer().getUniqueId();
    messageCountByPlayer.remove(uid);
    lastDenyTimeByPlayer.remove(uid);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!enabledInWorld(event.getPlayer().getWorld().getName())) return;
    Scheduler.runEntityTaskLater(event.getPlayer(), () -> scanAndRemoveExcess(event.getPlayer()), 2L);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onCraft(CraftItemEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    Recipe recipe = event.getRecipe();
    if (recipe == null) return;
    ItemStack result = recipe.getResult();
    if (result == null || result.getType().isAir()) return;
    Material m = result.getType();
    if (!limits.containsKey(m)) return;

    int craftedAmount = result.getAmount();
    if (event.getClick().isShiftClick() && event.getInventory() instanceof CraftingInventory craftInv) {
      craftedAmount = computeMaxCraftable(craftInv);
    }
    int current = countMaterial(player.getInventory(), m);
    if (current + craftedAmount > limits.get(m)) {
      event.setCancelled(true);
      deny(player, m, limits.get(m));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    InventoryView view = event.getView();
    ItemStack current = event.getCurrentItem();
    ItemStack cursor = event.getCursor();

    boolean intoPlayer = false;
    ItemStack checkStack = null;

    if (event.isShiftClick() && view.getTopInventory() != null
        && event.getClickedInventory() != null && event.getClickedInventory().equals(view.getTopInventory())) {
      intoPlayer = true;
      checkStack = current;
    } else if (cursor != null && !cursor.getType().isAir()
        && event.getClickedInventory() != null && event.getClickedInventory().equals(view.getBottomInventory())) {
      intoPlayer = true;
      checkStack = cursor;
    } else if (cursor != null && !cursor.getType().isAir()
        && event.getClickedInventory() != null && event.getClickedInventory().equals(view.getTopInventory())
        && isBundleView(view)) {
      intoPlayer = true;
      checkStack = cursor;
    } else if (event.getSlotType() == InventoryType.SlotType.RESULT && view.getTopInventory() != null
        && view.getType() != InventoryType.CRAFTING && view.getType() != InventoryType.WORKBENCH) {
      intoPlayer = true;
      checkStack = current;
    } else if (event.isShiftClick() && event.getSlotType() == InventoryType.SlotType.RESULT
        && view.getTopInventory() instanceof CraftingInventory craftInv
        && (view.getType() == InventoryType.CRAFTING || view.getType() == InventoryType.WORKBENCH)) {
      intoPlayer = true;
      int maxCraft = computeMaxCraftable(craftInv);
      if (maxCraft > 0 && current != null && !current.getType().isAir() && limits.containsKey(current.getType())) {
        int currentCount = countMaterial(player.getInventory(), current.getType());
        if (currentCount + maxCraft > limits.get(current.getType())) {
          event.setCancelled(true);
          deny(player, current.getType(), limits.get(current.getType()));
        }
      }
      return;
    }

    if (intoPlayer && checkStack != null && !checkStack.getType().isAir() && limits.containsKey(checkStack.getType())) {
      Material m = checkStack.getType();
      int limit = limits.get(m);
      int currentCount = countMaterial(player.getInventory(), m);
      int effectiveAdd = checkStack.getAmount();
      if (event.getClickedInventory() != null && event.getClickedInventory().equals(view.getBottomInventory())) {
        ItemStack inSlot = event.getCurrentItem();
        if (inSlot != null && inSlot.getType() == m)
          effectiveAdd -= inSlot.getAmount();
      }
      if (currentCount + effectiveAdd <= limit) return;

      event.setCancelled(true);
      if (event.isShiftClick() && event.getClickedInventory() != null && event.getClickedInventory().equals(view.getTopInventory())
          && !isBundleView(view)) {
        int canTake = getCanAdd(player, m, checkStack.getAmount());
        if (canTake > 0) {
          ItemStack toAdd = cloneAmount(checkStack, canTake);
          HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(toAdd);
          int overflowAmt = 0;
          for (ItemStack o : overflow.values()) {
            if (o != null && o.getType() == m) overflowAmt += o.getAmount();
          }
          int actualTaken = canTake - overflowAmt;
          int slot = event.getSlot();
          int remain = checkStack.getAmount() - actualTaken;
          if (remain <= 0) {
            view.getTopInventory().setItem(slot, null);
          } else {
            view.getTopInventory().setItem(slot, cloneAmount(checkStack, remain));
          }
          for (ItemStack o : overflow.values()) {
            if (o != null && !o.getType().isAir())
              player.getWorld().dropItemNaturally(player.getLocation(), o);
          }
          player.updateInventory();
        } else {
          deny(player, m, limit);
        }
      } else {
        deny(player, m, limit);
      }
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    ItemStack cursor = event.getOldCursor();
    if (cursor == null || cursor.getType().isAir() || !limits.containsKey(cursor.getType())) return;

    InventoryView v = event.getView();
    int topSize = v.getTopInventory() != null ? v.getTopInventory().getSize() : 0;
    boolean intoPlayer = false;
    for (int raw : event.getRawSlots()) {
      if (raw >= topSize || (raw < topSize && isBundleView(v))) { intoPlayer = true; break; }
    }
    if (intoPlayer && countMaterial(player.getInventory(), cursor.getType()) + cursor.getAmount() > limits.get(cursor.getType())) {
      event.setCancelled(true);
      deny(player, cursor.getType(), limits.get(cursor.getType()));
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    Player player = event.getPlayer();
    if (!enabledInWorld(player.getWorld().getName())) return;
    int graceTicks = Math.max(5, plugin.getConfig().getInt("item_limiter.swap_grace_ticks", 20));
    swapGracePlayers.add(player.getUniqueId());
    Scheduler.runTaskLater(() -> swapGracePlayers.remove(player.getUniqueId()), graceTicks);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onFish(PlayerFishEvent event) {
    if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
    Player player = event.getPlayer();
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    if (!(event.getCaught() instanceof Item drop)) return;
    ItemStack stack = drop.getItemStack();
    if (stack == null || stack.getType().isAir() || !limits.containsKey(stack.getType())) return;

    if (countMaterial(player.getInventory(), stack.getType()) + stack.getAmount() > limits.get(stack.getType())) {
      event.setCancelled(true);
      drop.remove();
      deny(player, stack.getType(), limits.get(stack.getType()));
    }
  }

  private void scanAndRemoveExcess(Player player) {
    if (player == null || !player.isOnline()) return;
    if (!enabledInWorld(player.getWorld().getName())) return;
    if (hasBypass(player)) return;

    Map<Material, Integer> lims = limits;
    if (lims.isEmpty()) return;

    Map<Material, Integer> counts = countAllLimited(player.getInventory(), lims);
    if (counts.isEmpty()) return;

    PlayerInventory inv = player.getInventory();
    boolean anyRemoved = false;
    boolean dropItems = dropExcess();
    Location dropLoc = player.getLocation();
    World world = dropLoc.getWorld();

    for (Map.Entry<Material, Integer> e : lims.entrySet()) {
      Material m = e.getKey();
      int limit = e.getValue();
      int current = counts.getOrDefault(m, 0);
      if (current <= limit) continue;

      int toRemove = current - limit;
      boolean trimmedThisMaterial = false;

      for (int i = 0; i < inv.getSize() && toRemove > 0; i++) {
        ItemStack s = inv.getItem(i);
        if (s == null || s.getType() != m) continue;
        int amt = s.getAmount();
        if (amt <= toRemove) {
          if (dropItems && world != null && amt > 0)
            world.dropItemNaturally(dropLoc, cloneAmount(s, amt));
          inv.setItem(i, null);
          toRemove -= amt;
          trimmedThisMaterial = true;
          anyRemoved = true;
        } else {
          if (dropItems && world != null && toRemove > 0)
            world.dropItemNaturally(dropLoc, cloneAmount(s, toRemove));
          s.setAmount(amt - toRemove);
          toRemove = 0;
          trimmedThisMaterial = true;
          anyRemoved = true;
        }
      }
      if (toRemove > 0) {
        int rem = removeFromBundles(inv, m, toRemove, dropItems, world, dropLoc);
        toRemove -= rem;
        if (rem > 0) { trimmedThisMaterial = true; anyRemoved = true; }
      }

      if (trimmedThisMaterial && plugin.getConfig().getBoolean("item_limiter.send_excess_messages", true)) {
        int msgInterval = plugin.getConfig().getInt("item_limiter.message_interval", 100);
        if (msgInterval > 0) {
          int count = messageCountByPlayer
              .computeIfAbsent(player.getUniqueId(), u -> new ConcurrentHashMap<>())
              .merge(m, 1, Integer::sum);
          if (count == 1 || count % msgInterval == 1) {
            Map<String, String> ph = new HashMap<>();
            ph.put("material", formatMaterial(m));
            ph.put("limit", String.valueOf(limit));
            plugin.getMessageService().sendMessage(player, "item_limiter_excess_removed", ph);
          }
        }
      }
    }

    if (anyRemoved) player.updateInventory();
  }

  private static ItemStack cloneAmount(ItemStack s, int amt) {
    ItemStack c = s.clone();
    c.setAmount(amt);
    return c;
  }

  private int removeFromBundles(PlayerInventory inv, Material m, int toRemove,
      boolean dropItems, World world, Location dropLoc) {
    int removed = 0;
    for (int i = 0; i < inv.getSize() && toRemove > 0; i++) {
      ItemStack s = inv.getItem(i);
      if (s == null || s.getType() != Material.BUNDLE) continue;
      if (!(s.getItemMeta() instanceof BundleMeta bm) || !bm.hasItems()) continue;
      List<ItemStack> items = new ArrayList<>(bm.getItems());
      List<ItemStack> kept = new ArrayList<>();
      for (ItemStack stack : items) {
        if (stack == null || stack.getType() != m) { kept.add(stack); continue; }
        int amt = stack.getAmount();
        if (amt <= toRemove) {
          if (dropItems && world != null && amt > 0) { world.dropItemNaturally(dropLoc, cloneAmount(stack, amt)); removed += amt; }
          toRemove -= amt;
        } else {
          if (dropItems && world != null && toRemove > 0) { world.dropItemNaturally(dropLoc, cloneAmount(stack, toRemove)); removed += toRemove; }
          kept.add(cloneAmount(stack, amt - toRemove));
          toRemove = 0;
        }
      }
      bm.setItems(kept.isEmpty() ? null : kept);
      s.setItemMeta(bm);
      inv.setItem(i, s);
    }
    return removed;
  }
}
