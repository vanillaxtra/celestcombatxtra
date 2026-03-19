package com.shyamstudio.celestcombatXtra.cooldown;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.language.ColorUtil;
import com.shyamstudio.celestcombatXtra.listeners.ItemRestrictionListener;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central cooldown tracker for Phase 1 features.
 *
 * - Wind charge cooldown: one cooldown per-player.
 * - General item cooldowns: Material (+ optional metaKey) per-player.
 * - UI: action bar countdowns, updated once per second.
 */
public final class ItemCooldownManager {

  public record CooldownKey(Material material, String metaKey) {}
  public record GeneralCooldownInfo(String itemName, int remainingSeconds) {}

  private final CelestCombatPro plugin;

  private final Map<UUID, Long> windChargeCooldownEnds = new ConcurrentHashMap<>();
  private final Map<UUID, Map<CooldownKey, Long>> generalItemCooldownEnds = new ConcurrentHashMap<>();

  private static final String WIND_CHARGE_ACTION_BAR_KEY = "windcharge_cooldown";
  private static final String GENERAL_ITEM_ACTION_BAR_KEY = "general_item_cooldown";

  private Scheduler.Task countdownTask;

  public ItemCooldownManager(CelestCombatPro plugin) {
    this.plugin = plugin;
    startCountdownTask();
  }

  private void startCountdownTask() {
    countdownTask = Scheduler.runTaskTimer(() -> {
      long now = System.currentTimeMillis();

      // Wind charge cleanup
      windChargeCooldownEnds.entrySet().removeIf(entry -> {
        UUID uuid = entry.getKey();
        Long end = entry.getValue();
        return end == null || now > end || plugin.getServer().getPlayer(uuid) == null;
      });

      // General cleanup
      generalItemCooldownEnds.entrySet().removeIf(entry -> {
        UUID uuid = entry.getKey();
        Map<CooldownKey, Long> map = entry.getValue();
        if (map == null || plugin.getServer().getPlayer(uuid) == null) {
          return true;
        }

        map.entrySet().removeIf(e -> e.getValue() == null || now > e.getValue());
        return map.isEmpty();
      });

      // Actionbar updates
      for (UUID uuid : windChargeCooldownEnds.keySet()) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) continue;
        if (shouldNotOverwriteCoreActionBar(player)) continue;

        long end = windChargeCooldownEnds.getOrDefault(uuid, 0L);
        if (now >= end) continue;

        int remainingSeconds = secondsCeil(end - now);
        if (remainingSeconds <= 0) continue;

        sendActionBarOnly(player, WIND_CHARGE_ACTION_BAR_KEY,
            Map.of("time", String.valueOf(remainingSeconds)));
      }

      // General item cooldown action bars
      for (Map.Entry<UUID, Map<CooldownKey, Long>> entry : generalItemCooldownEnds.entrySet()) {
        UUID uuid = entry.getKey();
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) continue;
        if (shouldNotOverwriteCoreActionBar(player)) continue;

        // If wind charge is active, don't overwrite its action bar.
        if (windChargeCooldownEnds.containsKey(uuid)) continue;

        Map<CooldownKey, Long> cooldowns = entry.getValue();
        if (cooldowns == null || cooldowns.isEmpty()) continue;

        CooldownKey soonestKey = cooldowns.entrySet().stream()
            .filter(e -> e.getValue() != null && now < e.getValue())
            .min(Comparator.comparingLong(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(null);

        if (soonestKey == null) continue;

        long end = cooldowns.getOrDefault(soonestKey, 0L);
        if (now >= end) continue;

        int remainingSeconds = secondsCeil(end - now);
        if (remainingSeconds <= 0) continue;

        String itemName = ItemRestrictionListener.formatItemName(soonestKey.material());
        sendActionBarOnly(player, GENERAL_ITEM_ACTION_BAR_KEY,
            Map.of(
                "item", itemName,
                "time", String.valueOf(remainingSeconds)
            ));
      }
    }, 0L, 20L);
  }

  /**
   * Sends only the action bar line from language files — no chat message or sound.
   */
  private void sendActionBarOnly(Player player, String messageKey, Map<String, String> placeholders) {
    if (player == null || !player.isOnline()) return;
    String line = plugin.getLanguageManager().getActionBar(messageKey, placeholders);
    if (line == null) return;
    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
        TextComponent.fromLegacyText(ColorUtil.translateHexColorCodes(line)));
  }

  private boolean shouldNotOverwriteCoreActionBar(Player player) {
    if (player == null) return true;
    // Match existing pearl/trident/combat countdown behavior by yielding to core actionbars.
    return plugin.getCombatManager().isInCombat(player)
            || plugin.getCombatManager().isEnderPearlOnCooldown(player)
            || plugin.getCombatManager().isTridentOnCooldown(player);
  }

  private static int secondsCeil(long remainingMs) {
    if (remainingMs <= 0) return 0;
    return (int) Math.ceil(remainingMs / 1000.0);
  }

  public boolean isWindChargeOnCooldown(Player player) {
    if (player == null) return false;
    UUID uuid = player.getUniqueId();
    long end = windChargeCooldownEnds.getOrDefault(uuid, 0L);
    if (end <= 0) return false;

    long now = System.currentTimeMillis();
    if (now > end) {
      windChargeCooldownEnds.remove(uuid);
      return false;
    }
    return true;
  }

  public int getRemainingWindChargeCooldown(Player player) {
    if (player == null) return 0;
    UUID uuid = player.getUniqueId();
    long end = windChargeCooldownEnds.getOrDefault(uuid, 0L);
    if (end <= 0) return 0;

    return secondsCeil(end - System.currentTimeMillis());
  }

  /**
   * Remaining cooldown in server ticks (50ms each), aligned with {@link Player#setCooldown}.
   */
  public int getRemainingWindChargeCooldownTicks(Player player) {
    if (player == null) return 0;
    UUID uuid = player.getUniqueId();
    long end = windChargeCooldownEnds.getOrDefault(uuid, 0L);
    if (end <= 0) return 0;

    long remainingMs = end - System.currentTimeMillis();
    if (remainingMs <= 0) return 0;
    return Math.max(1, (int) Math.ceil(remainingMs / 50.0));
  }

  public void startWindChargeCooldown(Player player, long durationMs) {
    if (player == null) return;
    if (durationMs <= 0) return;

    long newEnd = System.currentTimeMillis() + durationMs;
    UUID uuid = player.getUniqueId();
    windChargeCooldownEnds.merge(uuid, newEnd, Math::max);

    int ticks = Math.max(1, (int) Math.ceil(durationMs / 50.0));
    // Same-tick apply (may be overwritten when vanilla finishes the wind-charge use).
    player.setCooldown(Material.WIND_CHARGE, ticks);
    // Re-apply next tick so the hotbar overlay sticks (vanilla use/cooldown runs after this event).
    scheduleWindChargeClientCooldownRefresh(player);
  }

  /**
   * Re-sends {@link Player#setCooldown} for wind charges on the following tick(s), using the
   * plugin’s remaining time. Vanilla clears or replaces same-tick cooldown when a wind charge
   * is actually consumed/launched, which is why blocking a second use “worked” but the first did not.
   */
  public void scheduleWindChargeClientCooldownRefresh(Player player) {
    if (player == null) return;
    UUID uuid = player.getUniqueId();
    Runnable apply = () -> {
      Player p = plugin.getServer().getPlayer(uuid);
      if (p == null || !p.isOnline()) return;
      int t = getRemainingWindChargeCooldownTicks(p);
      if (t > 0) {
        p.setCooldown(Material.WIND_CHARGE, t);
      }
    };
    Scheduler.runEntityTaskLater(player, apply, 1L);
    // One extra tick covers late NMS/item updates on some Paper builds.
    Scheduler.runEntityTaskLater(player, apply, 2L);
  }

  public boolean isGeneralItemOnCooldown(Player player, CooldownKey key) {
    if (player == null || key == null) return false;

    UUID uuid = player.getUniqueId();
    Map<CooldownKey, Long> map = generalItemCooldownEnds.get(uuid);
    if (map == null) return false;

    long end = map.getOrDefault(key, 0L);
    if (end <= 0) return false;

    if (System.currentTimeMillis() > end) {
      map.remove(key);
      return false;
    }
    return true;
  }

  public int getRemainingGeneralItemCooldown(Player player, CooldownKey key) {
    if (player == null || key == null) return 0;

    UUID uuid = player.getUniqueId();
    Map<CooldownKey, Long> map = generalItemCooldownEnds.get(uuid);
    if (map == null) return 0;

    long end = map.getOrDefault(key, 0L);
    if (end <= 0) return 0;

    return secondsCeil(end - System.currentTimeMillis());
  }

  /** Remaining time in ticks for {@link Player#setCooldown} (50ms per tick). */
  public int getRemainingGeneralItemCooldownTicks(Player player, CooldownKey key) {
    if (player == null || key == null) return 0;
    UUID uuid = player.getUniqueId();
    Map<CooldownKey, Long> map = generalItemCooldownEnds.get(uuid);
    if (map == null) return 0;
    long end = map.getOrDefault(key, 0L);
    if (end <= 0) return 0;
    long remainingMs = end - System.currentTimeMillis();
    if (remainingMs <= 0) return 0;
    return Math.max(1, (int) Math.ceil(remainingMs / 50.0));
  }

  /**
   * Returns the soonest active general item cooldown for the given player.
   * This is used to merge cooldowns into the combat/pearl/trident action bar.
   */
  public GeneralCooldownInfo getSoonestGeneralItemCooldownInfo(Player player) {
    return getSoonestGeneralItemCooldownInfo(player, null);
  }

  /**
   * @param excludeMaterial optional material to omit (e.g. MACE when it is shown via {@code mace_cooldown})
   */
  public GeneralCooldownInfo getSoonestGeneralItemCooldownInfo(Player player, Material excludeMaterial) {
    if (player == null) return null;
    UUID uuid = player.getUniqueId();
    Map<CooldownKey, Long> cooldowns = generalItemCooldownEnds.get(uuid);
    if (cooldowns == null || cooldowns.isEmpty()) return null;

    long now = System.currentTimeMillis();
    CooldownKey soonestKey = cooldowns.entrySet().stream()
        .filter(e -> e.getValue() != null && now < e.getValue())
        .filter(e -> excludeMaterial == null || e.getKey().material() != excludeMaterial)
        .min(Comparator.comparingLong(Map.Entry::getValue))
        .map(Map.Entry::getKey)
        .orElse(null);

    if (soonestKey == null) return null;

    long end = cooldowns.getOrDefault(soonestKey, 0L);
    if (now >= end) return null;

    int remainingSeconds = secondsCeil(end - now);
    if (remainingSeconds <= 0) return null;

    String itemName = ItemRestrictionListener.formatItemName(soonestKey.material());
    return new GeneralCooldownInfo(itemName, remainingSeconds);
  }

  /**
   * Appends wind, mace, and other general item cooldown segments (same order as combat merge).
   */
  public void appendMergedCooldownSuffix(StringBuilder merged, Player player, boolean appendWindCharge) {
    if (merged == null || player == null || !player.isOnline()) return;

    if (appendWindCharge && isWindChargeOnCooldown(player)) {
      int remainingWind = getRemainingWindChargeCooldown(player);
      String windActionBar = plugin.getLanguageManager().getActionBar(
          "windcharge_cooldown", Map.of("time", String.valueOf(remainingWind)));
      if (windActionBar != null) {
        merged.append(ColorUtil.translateHexColorCodes(" &#8B8B8B| ")).append(windActionBar);
      }
    }

    CooldownKey maceKey = new CooldownKey(Material.MACE, null);
    boolean maceCd = isGeneralItemOnCooldown(player, maceKey);
    if (maceCd) {
      int remainingMace = getRemainingGeneralItemCooldown(player, maceKey);
      String maceLine = plugin.getLanguageManager().getActionBar(
          "mace_cooldown", Map.of("time", String.valueOf(remainingMace)));
      if (maceLine != null) {
        merged.append(ColorUtil.translateHexColorCodes(" &#8B8B8B| ")).append(maceLine);
      }
    }

    GeneralCooldownInfo generalInfo = getSoonestGeneralItemCooldownInfo(player, maceCd ? Material.MACE : null);
    if (generalInfo != null) {
      String generalActionBar = plugin.getLanguageManager().getActionBar(
          "general_item_cooldown",
          Map.of(
              "item", generalInfo.itemName(),
              "time", String.valueOf(generalInfo.remainingSeconds())
          ));
      if (generalActionBar != null) {
        merged.append(ColorUtil.translateHexColorCodes(" &#8B8B8B| ")).append(generalActionBar);
      }
    }
  }

  public void startGeneralCooldown(Player player, CooldownKey key, long durationMs) {
    if (player == null || key == null) return;
    if (durationMs <= 0) return;

    UUID uuid = player.getUniqueId();
    long newEnd = System.currentTimeMillis() + durationMs;

    generalItemCooldownEnds
        .computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>())
        .merge(key, newEnd, Math::max);

    // Show real client-side item cooldown overlay.
    int ticks = (int) Math.ceil(durationMs / 50.0);
    if (ticks > 0 && key.material() != null) {
      player.setCooldown(key.material(), ticks);
    }
    scheduleGeneralItemCooldownRefresh(player, key);
  }

  /**
   * Re-applies {@link Player#setCooldown} next tick(s) so the overlay sticks after consume/interact
   * (vanilla often clears or overwrites same-tick cooldown).
   */
  private void scheduleGeneralItemCooldownRefresh(Player player, CooldownKey key) {
    if (player == null || key == null || key.material() == null) return;
    UUID uuid = player.getUniqueId();
    Material mat = key.material();
    Runnable apply = () -> {
      Player p = plugin.getServer().getPlayer(uuid);
      if (p == null || !p.isOnline()) return;
      int t = getRemainingGeneralItemCooldownTicks(p, key);
      if (t > 0) {
        p.setCooldown(mat, t);
      }
    };
    Scheduler.runEntityTaskLater(player, apply, 1L);
    Scheduler.runEntityTaskLater(player, apply, 2L);
  }

  public void shutdown() {
    if (countdownTask != null) {
      countdownTask.cancel();
      countdownTask = null;
    }

    windChargeCooldownEnds.clear();
    generalItemCooldownEnds.clear();
  }
}

