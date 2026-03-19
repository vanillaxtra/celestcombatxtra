package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Egg;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.configs.RestrictionConfigPaths;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager;
import com.shyamstudio.celestcombatXtra.cooldown.ItemCooldownManager.CooldownKey;
import com.shyamstudio.celestcombatXtra.cooldown.UseCooldowns;
import com.shyamstudio.celestcombatXtra.language.ColorUtil;
import com.shyamstudio.celestcombatXtra.language.MessageService;

import io.papermc.paper.event.player.PlayerArmSwingEvent;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GeneralItemCooldownListener implements Listener {

  // Not all Paper versions expose a COPPER_HORN Material constant; use matchMaterial for safety.
  private static final Material COPPER_HORN_MATERIAL = Material.matchMaterial("COPPER_HORN");
  private static final Material SPEAR_MATERIAL = Material.matchMaterial("SPEAR");
  private static final Material ICE_BOMB_MATERIAL = Material.matchMaterial("ICE_BOMB");
  private static final CooldownKey MACE_COOLDOWN_KEY = new CooldownKey(Material.MACE, null);

  private final CelestCombatPro plugin;
  private final ItemCooldownManager cooldownManager;
  private final MessageService messageService;

  private boolean enabled;
  private boolean cooldownedItemsEnabled;
  private final Map<Material, List<GeneralCooldownDefinition>> defsByMaterial = new ConcurrentHashMap<>();
  private MaceRestrictionSettings maceSettings;

  public GeneralItemCooldownListener(CelestCombatPro plugin, ItemCooldownManager cooldownManager) {
    this.plugin = plugin;
    this.cooldownManager = cooldownManager;
    this.messageService = plugin.getMessageService();
    reloadFromConfig();
  }

  /**
   * Re-reads toggles, mace settings, and cooldowned item entries (call after config reload).
   *
   * @return materials skipped because they use dedicated modules (pearls, trident, wind charge, elytra, mace)
   */
  public List<String> reloadFromConfig() {
    String ir = RestrictionConfigPaths.itemRestrictionsRoot(plugin.getConfig());
    boolean masterIr = plugin.getConfig().getBoolean(ir + ".enabled", true);
    this.cooldownedItemsEnabled = plugin.getConfig().getBoolean(ir + ".cooldowned_items.enabled", true)
            || plugin.getConfig().getBoolean("general_item_cooldowns.enabled", false);
    this.enabled = masterIr && this.cooldownedItemsEnabled;
    this.maceSettings = loadMaceSettings();
    return loadDefinitions();
  }

  private MaceRestrictionSettings loadMaceSettings() {
    boolean useNewBlock = plugin.getConfig().get("mace") != null;

    boolean maceEnabled = useNewBlock
            ? plugin.getConfig().getBoolean("mace.enabled", true)
            : plugin.getConfig().getBoolean("mace_restrictions.enabled", true);

    String modeRaw = useNewBlock
            ? plugin.getConfig().getString("mace.attack_mode", "player_hit_only")
            : plugin.getConfig().getString("mace_restrictions.attack_mode", "player_hit_only");
    MaceAttackMode mode = "any_attack".equalsIgnoreCase(modeRaw)
            ? MaceAttackMode.ANY_ATTACK
            : MaceAttackMode.PLAYER_HIT_ONLY;

    String durationStr;
    if (useNewBlock) {
      Object d = plugin.getConfig().get("mace.duration");
      durationStr = d != null ? d.toString() : "5s";
    } else {
      durationStr = plugin.getConfig().getString("mace_restrictions.cooldown.duration", "5s");
    }
    long durationTicks = plugin.getTimeFormatter().parseTimeToTicks(durationStr, 5L * 20L);
    long cooldownMs = Math.max(0L, durationTicks * 50L);

    boolean allowOutOfCombat = useNewBlock
            ? plugin.getConfig().getBoolean("mace.allow_out_of_combat", true)
            : !plugin.getConfig().getBoolean("mace_restrictions.disable_out_of_combat", false);

    return new MaceRestrictionSettings(maceEnabled, mode, cooldownMs, allowOutOfCombat);
  }

  private List<String> loadDefinitions() {
    defsByMaterial.clear();
    List<String> skippedReserved = new ArrayList<>();

    if (!cooldownedItemsEnabled) {
      return skippedReserved;
    }

    String entriesPath = RestrictionConfigPaths.cooldownedEntriesPath(plugin.getConfig());
    List<Map<?, ?>> entries = plugin.getConfig().getMapList(entriesPath);
    for (Map<?, ?> raw : entries) {
      String materialStr = raw.get("material") != null ? raw.get("material").toString() : null;
      if (materialStr == null) continue;

      Material material = Material.matchMaterial(materialStr);
      if (material == null) {
        // Some items (or renamed materials) can be missing depending on the Paper API version.
        // Avoid noisy warnings for known optional materials.
        if (!isKnownOptionalMissingMaterial(materialStr)) {
          plugin.getLogger().warning("[CelestCombatXtra] Invalid material in cooldowned_items: " + materialStr);
        }
        continue;
      }

      if (isReservedCooldownMaterial(material)) {
        skippedReserved.add(material.name());
        plugin.getLogger().warning(
            "[CelestCombatXtra] Ignoring cooldowned_items entry for " + material.name()
                + " — use enderpearl, trident, windcharge, elytra/item_restrictions, or mace config instead.");
        continue;
      }

      PotionType potionType = null;
      Object metaObj = raw.get("meta");
      if (metaObj instanceof Map<?, ?> metaMap) {
        Object potionTypeStr = metaMap.get("potion_type");
        if (potionTypeStr != null) {
          try {
            potionType = PotionType.valueOf(potionTypeStr.toString().toUpperCase());
          } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("[CelestCombatXtra] Invalid potion_type for " + materialStr + ": " + potionTypeStr);
          }
        }
      }

      boolean disableInCombat = parseConfigBool(raw, "disable_in_combat", false);
      boolean disableOutOfCombat = parseConfigBool(raw, "disable_out_of_combat", false);
      // When false: cooldown rules only apply while the player is in combat.
      boolean applyCooldownOutOfCombat = parseConfigBool(raw, "cooldown_out_of_combat", true);

      long durationMs = parseCooldownDurationMs(raw);
      if (durationMs <= 0) continue;

      defsByMaterial.computeIfAbsent(material, k -> new ArrayList<>()).add(
          new GeneralCooldownDefinition(
              material, potionType, durationMs, disableInCombat, disableOutOfCombat, applyCooldownOutOfCombat)
      );
    }
    return skippedReserved;
  }

  /**
   * Items that already have dedicated cooldown / combat modules — never stack custom cooldown entries.
   */
  private static boolean isReservedCooldownMaterial(Material material) {
    if (material == null) return false;
    return material == Material.ENDER_PEARL
        || material == Material.TRIDENT
        || material == Material.WIND_CHARGE
        || material == Material.ELYTRA
        || material == Material.MACE;
  }

  private static boolean parseConfigBool(Map<?, ?> raw, String key, boolean defaultValue) {
    if (raw == null || !raw.containsKey(key)) {
      return defaultValue;
    }
    Object v = raw.get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof String s) {
      if ("true".equalsIgnoreCase(s)) return true;
      if ("false".equalsIgnoreCase(s)) return false;
    }
    return Boolean.parseBoolean(String.valueOf(v));
  }

  private long parseCooldownDurationMs(Map<?, ?> raw) {
    Object cooldownObj = raw.get("cooldown");
    if (cooldownObj == null) {
      return 0L;
    }
    if (cooldownObj instanceof String s) {
      long ticks = plugin.getTimeFormatter().parseTimeToTicks(s, 10L * 20L);
      return ticks * 50L;
    }
    if (cooldownObj instanceof Number n) {
      return n.longValue() * 50L;
    }
    if (cooldownObj instanceof Map<?, ?> cooldownMap) {
      Object durationStr = cooldownMap.get("duration");
      if (durationStr instanceof Number num) {
        return num.longValue() * 50L;
      }
      if (durationStr != null) {
        long ticks = plugin.getTimeFormatter().parseTimeToTicks(durationStr.toString(), 10L * 20L);
        return ticks * 50L;
      }
    }
    return 0L;
  }

  /** When {@code applyCooldownOutOfCombat} is false, ignore cooldown rules outside combat. */
  private boolean skipCooldownScope(Player player, GeneralCooldownDefinition def) {
    if (player == null) return true;
    return !def.applyCooldownOutOfCombat() && !plugin.getCombatManager().isInCombat(player);
  }

  /**
   * Cancels arm swing while the mace is on plugin cooldown or disallowed out-of-combat,
   * so the client does not play attack animations for a hit that will not land.
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onMaceSwing(PlayerArmSwingEvent event) {
    if (!maceSettings.enabled()) return;

    Player player = event.getPlayer();
    ItemStack inHand = event.getHand() == EquipmentSlot.HAND
        ? player.getInventory().getItemInMainHand()
        : player.getInventory().getItemInOffHand();
    if (inHand == null || inHand.getType() != Material.MACE) return;

    boolean inCombat = plugin.getCombatManager().isInCombat(player);
    if (!inCombat && !maceSettings.allowOutOfCombat()) {
      event.setCancelled(true);
      messageService.sendMessage(player, "general_item_disabled_out_of_combat",
          Map.of("item", ItemRestrictionListener.formatItemName(Material.MACE)));
      return;
    }

    if (!cooldownManager.isGeneralItemOnCooldown(player, MACE_COOLDOWN_KEY)) {
      return;
    }

    event.setCancelled(true);
    int remaining = cooldownManager.getRemainingGeneralItemCooldown(player, MACE_COOLDOWN_KEY);
    int remainingTicks = cooldownManager.getRemainingGeneralItemCooldownTicks(player, MACE_COOLDOWN_KEY);
    if (remainingTicks > 0) {
      player.setCooldown(Material.MACE, remainingTicks);
    }
    sendMaceCooldownActionBar(player, remaining);
  }

  private void sendMaceCooldownActionBar(Player player, int remainingSeconds) {
    if (player == null || !player.isOnline()) return;
    String line = plugin.getLanguageManager().getActionBar(
        "mace_cooldown", Map.of("time", String.valueOf(remainingSeconds)));
    if (line == null) return;
    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
        TextComponent.fromLegacyText(ColorUtil.translateHexColorCodes(line)));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onConsume(PlayerItemConsumeEvent event) {
    if (!enabled) return;

    Player player = event.getPlayer();
    ItemStack item = event.getItem();
    if (item == null || item.getType() == Material.AIR) return;

    GeneralCooldownDefinition def = matchDefinition(item);
    if (def == null) return;
    if (skipCooldownScope(player, def)) return;

    CooldownKey key = toKey(def);
    boolean inCombat = plugin.getCombatManager().isInCombat(player);

    if ((inCombat && def.disableInCombat) || (!inCombat && def.disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(player, def.material);
      sendRestrictionActionBar(
          player,
          inCombat ? "general_item_disabled_in_combat" : "general_item_disabled_out_of_combat",
          Map.of("item", def.material.name())
      );
      return;
    }

    if (cooldownManager.isGeneralItemOnCooldown(player, key)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingGeneralItemCooldown(player, key);
      int remainingTicks = cooldownManager.getRemainingGeneralItemCooldownTicks(player, key);
      if (remainingTicks > 0) {
        player.setCooldown(def.material, remainingTicks);
      }
      Map<String, String> placeholders = new HashMap<>();
      placeholders.put("item", def.material.name());
      placeholders.put("time", String.valueOf(remaining));
      if (!shouldSkipCooldownActionBar(player)) {
        messageService.sendMessage(player, "general_item_cooldown", placeholders);
      }
      return;
    }

    cooldownManager.startGeneralCooldown(player, key, def.cooldownMs);
  }

  /**
   * Covers "right-click use" items that aren't consumptions/placements/tool hits.
   * Currently: goat horn, copper horn, shield (when present in cooldowned_items).
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (!enabled) return;

    if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
        && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
      return;
    }

    Player player = event.getPlayer();
    ItemStack item = event.getItem();
    if (item == null || item.getType() == Material.AIR) return;

    // Only handle specific "use cooldown" items here to avoid double-starting chorus fruit.
    boolean isGoatHorn = item.getType() == Material.GOAT_HORN;
    boolean isShield = item.getType() == Material.SHIELD;
    boolean isCopperHorn = COPPER_HORN_MATERIAL != null && item.getType() == COPPER_HORN_MATERIAL;
    if (!(isGoatHorn || isShield || isCopperHorn)) {
      return;
    }

    GeneralCooldownDefinition def = matchDefinition(item);
    if (def == null) return;
    if (skipCooldownScope(player, def)) return;

    CooldownKey key = toKey(def);
    boolean inCombat = plugin.getCombatManager().isInCombat(player);

    if ((inCombat && def.disableInCombat) || (!inCombat && def.disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(player, def.material);
      sendRestrictionActionBar(
          player,
          inCombat ? "general_item_disabled_in_combat" : "general_item_disabled_out_of_combat",
          Map.of("item", def.material.name())
      );
      return;
    }

    if (cooldownManager.isGeneralItemOnCooldown(player, key)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingGeneralItemCooldown(player, key);
      int remainingTicks = cooldownManager.getRemainingGeneralItemCooldownTicks(player, key);
      if (remainingTicks > 0) {
        player.setCooldown(def.material, remainingTicks);
      }

      Map<String, String> placeholders = new HashMap<>();
      placeholders.put("item", def.material.name());
      placeholders.put("time", String.valueOf(remaining));
      if (!shouldSkipCooldownActionBar(player)) {
        messageService.sendMessage(player, "general_item_cooldown", placeholders);
      }
      return;
    }

    // Start cooldown (client overlay is set inside ItemCooldownManager).
    cooldownManager.startGeneralCooldown(player, key, def.cooldownMs);
  }

  /**
   * Covers throwable "use cooldown" items: snowballs and eggs.
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onProjectileLaunch(ProjectileLaunchEvent event) {
    if (!(event.getEntity() instanceof org.bukkit.entity.Projectile projectile)) return;
    if (!(event.getEntity().getShooter() instanceof Player player)) return;

    Material material = null;
    if (projectile instanceof Snowball) {
      material = Material.SNOWBALL;
    } else if (projectile instanceof Egg) {
      material = Material.EGG;
    } else if (SPEAR_MATERIAL != null && player.getInventory().getItemInMainHand().getType() == SPEAR_MATERIAL) {
      // Spear throws start cooldown when the projectile launches.
      material = SPEAR_MATERIAL;
    } else if (ICE_BOMB_MATERIAL != null && player.getInventory().getItemInMainHand().getType() == ICE_BOMB_MATERIAL) {
      material = ICE_BOMB_MATERIAL;
    }

    if (material == null) return;
    if (!enabled) return;

    GeneralCooldownDefinition def = matchDefinitionForMaterial(material);
    if (def == null) return;
    if (skipCooldownScope(player, def)) return;

    CooldownKey key = toKey(def);
    boolean inCombat = plugin.getCombatManager().isInCombat(player);

    if ((inCombat && def.disableInCombat) || (!inCombat && def.disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(player, def.material);
      sendRestrictionActionBar(
          player,
          inCombat ? "general_item_disabled_in_combat" : "general_item_disabled_out_of_combat",
          Map.of("item", def.material.name())
      );
      return;
    }

    if (cooldownManager.isGeneralItemOnCooldown(player, key)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingGeneralItemCooldown(player, key);
      int remainingTicks = cooldownManager.getRemainingGeneralItemCooldownTicks(player, key);
      if (remainingTicks > 0) {
        player.setCooldown(def.material, remainingTicks);
      }

      Map<String, String> placeholders = new HashMap<>();
      placeholders.put("item", def.material.name());
      placeholders.put("time", String.valueOf(remaining));
      if (!shouldSkipCooldownActionBar(player)) {
        messageService.sendMessage(player, "general_item_cooldown", placeholders);
      }
      return;
    }

    cooldownManager.startGeneralCooldown(player, key, def.cooldownMs);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    if (!enabled) return;

    Player player = event.getPlayer();
    ItemStack item = event.getItemInHand();
    if (item == null || item.getType() == Material.AIR) return;

    GeneralCooldownDefinition def = matchDefinition(item);
    if (def == null) return;
    if (skipCooldownScope(player, def)) return;

    CooldownKey key = toKey(def);
    boolean inCombat = plugin.getCombatManager().isInCombat(player);

    if ((inCombat && def.disableInCombat) || (!inCombat && def.disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(player, def.material);
      sendRestrictionActionBar(
          player,
          inCombat ? "general_item_disabled_in_combat" : "general_item_disabled_out_of_combat",
          Map.of("item", def.material.name())
      );
      return;
    }

    if (cooldownManager.isGeneralItemOnCooldown(player, key)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingGeneralItemCooldown(player, key);
      int remainingTicks = cooldownManager.getRemainingGeneralItemCooldownTicks(player, key);
      if (remainingTicks > 0) {
        player.setCooldown(def.material, remainingTicks);
      }
      Map<String, String> placeholders = new HashMap<>();
      placeholders.put("item", def.material.name());
      placeholders.put("time", String.valueOf(remaining));
      if (!shouldSkipCooldownActionBar(player)) {
        messageService.sendMessage(player, "general_item_cooldown", placeholders);
      }
      return;
    }

    cooldownManager.startGeneralCooldown(player, key, def.cooldownMs);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityDamage(EntityDamageByEntityEvent event) {
    if (!(event.getDamager() instanceof Player attacker)) return;
    if (attacker.equals(event.getEntity())) return;

    ItemStack weapon = attacker.getInventory().getItemInMainHand();
    if (weapon == null || weapon.getType() == Material.AIR) return;
    if (!enabled && weapon.getType() != Material.MACE) return;
    if (!enabled && weapon.getType() == Material.MACE && !maceSettings.enabled()) return;

    GeneralCooldownDefinition def = resolveDefinitionForHit(weapon, event.getEntity() instanceof Player);
    if (def == null) return;
    if (skipCooldownScope(attacker, def)) return;

    CooldownKey key = toKey(def);
    boolean inCombat = plugin.getCombatManager().isInCombat(attacker);

    if ((inCombat && def.disableInCombat) || (!inCombat && def.disableOutOfCombat)) {
      event.setCancelled(true);
      UseCooldowns.applyUseCooldownIfKnown(attacker, def.material);
      sendRestrictionActionBar(
          attacker,
          inCombat ? "general_item_disabled_in_combat" : "general_item_disabled_out_of_combat",
          Map.of("item", def.material.name())
      );
      return;
    }

    if (cooldownManager.isGeneralItemOnCooldown(attacker, key)) {
      event.setCancelled(true);
      int remaining = cooldownManager.getRemainingGeneralItemCooldown(attacker, key);
      int remainingTicks = cooldownManager.getRemainingGeneralItemCooldownTicks(attacker, key);
      if (remainingTicks > 0) {
        attacker.setCooldown(def.material, remainingTicks);
      }
      Map<String, String> placeholders = new HashMap<>();
      placeholders.put("item", def.material.name());
      placeholders.put("time", String.valueOf(remaining));
      if (def.material == Material.MACE) {
        sendMaceCooldownActionBar(attacker, remaining);
      } else if (!shouldSkipCooldownActionBar(attacker)) {
        messageService.sendMessage(attacker, "general_item_cooldown", placeholders);
      }
      return;
    }

    cooldownManager.startGeneralCooldown(attacker, key, def.cooldownMs);
  }

  private void sendRestrictionActionBar(Player player, String key, Map<String, String> placeholders) {
    if (player == null || key == null) return;
    messageService.sendMessage(player, key, placeholders);
  }

  private boolean shouldSkipCooldownActionBar(Player player) {
    if (player == null) return true;
    // During merged combat/pearl/trident action bars we don't want a standalone cooldown
    // action bar to overwrite the merged output.
    return plugin.getCombatManager().isInCombat(player)
        || plugin.getCombatManager().isEnderPearlOnCooldown(player)
        || plugin.getCombatManager().isTridentOnCooldown(player);
  }

  private GeneralCooldownDefinition resolveDefinitionForHit(ItemStack weapon, boolean targetIsPlayer) {
    if (weapon == null) return null;

    if (weapon.getType() == Material.MACE && maceSettings.enabled()) {
      if (maceSettings.attackMode() == MaceAttackMode.PLAYER_HIT_ONLY && !targetIsPlayer) {
        return null;
      }
      return new GeneralCooldownDefinition(
              Material.MACE,
              null,
              maceSettings.cooldownMs(),
              false,
              !maceSettings.allowOutOfCombat(),
              true
      );
    }

    return matchDefinition(weapon);
  }

  private GeneralCooldownDefinition matchDefinition(ItemStack item) {
    if (item == null) return null;

    Material material = item.getType();
    List<GeneralCooldownDefinition> candidates = defsByMaterial.get(material);
    if (candidates == null || candidates.isEmpty()) return null;

    PotionType potionType = getPotionType(item);
    if (potionType != null) {
      for (GeneralCooldownDefinition def : candidates) {
        if (def.potionType != null && def.potionType.equals(potionType)) {
          return def;
        }
      }
    }

    // fallback: first "material-only" definition
    for (GeneralCooldownDefinition def : candidates) {
      if (def.potionType == null) {
        return def;
      }
    }

    return null;
  }

  private GeneralCooldownDefinition matchDefinitionForMaterial(Material material) {
    if (material == null) return null;
    List<GeneralCooldownDefinition> candidates = defsByMaterial.get(material);
    if (candidates == null || candidates.isEmpty()) return null;

    // For non-meta items (snowballs/eggs), prefer material-only definitions.
    for (GeneralCooldownDefinition def : candidates) {
      if (def.potionType == null) return def;
    }

    // Fallback: first candidate.
    return candidates.get(0);
  }

  private PotionType getPotionType(ItemStack item) {
    if (item == null) return null;
    if (!(item.getItemMeta() instanceof PotionMeta meta)) return null;

    try {
      return meta.getBasePotionData().getType();
    } catch (Exception ignored) {
      return null;
    }
  }

  private boolean isKnownOptionalMissingMaterial(String materialStr) {
    if (materialStr == null) return false;
    String m = materialStr.trim();
    return m.equalsIgnoreCase("COPPER_HORN") || m.equalsIgnoreCase("ICE_BOMB");
  }

  private static CooldownKey toKey(GeneralCooldownDefinition def) {
    return new CooldownKey(def.material, def.potionType != null ? def.potionType.name() : null);
  }

  private record GeneralCooldownDefinition(
      Material material,
      PotionType potionType,
      long cooldownMs,
      boolean disableInCombat,
      boolean disableOutOfCombat,
      boolean applyCooldownOutOfCombat
  ) {}

  private enum MaceAttackMode {
    ANY_ATTACK,
    PLAYER_HIT_ONLY
  }

  private record MaceRestrictionSettings(
      boolean enabled,
      MaceAttackMode attackMode,
      long cooldownMs,
      boolean allowOutOfCombat
  ) {}
}

