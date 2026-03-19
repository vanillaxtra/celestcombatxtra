package com.shyamstudio.celestcombatXtra.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional restrictions for crystal PvP, respawn anchors, nether/end beds, and TNT minecarts.
 * <p>
 * Each category can block the initiating action and/or strip entity damage / block breaking from
 * explosions that still occur (e.g. crystals placed before rules applied).
 */
public final class ExplosiveControlsListener implements Listener {

  private static final long EXPLOSION_TAG_MS = 3500L;
  /** Anchor/bed blocks are gone before player damage runs; match by recent explosion center. */
  private static final double EXPLOSION_TAG_RADIUS = 20.0;

  private enum ExplosiveKind {
    RESPAWN_ANCHOR,
    BED_NETHER_END
  }

  private record ExplosionTag(Location center, long timeMs, ExplosiveKind kind) {}

  private final CelestCombatPro plugin;
  private final CopyOnWriteArrayList<ExplosionTag> recentExplosions = new CopyOnWriteArrayList<>();

  public ExplosiveControlsListener(CelestCombatPro plugin) {
    this.plugin = plugin;
  }

  private void registerExplosionTag(Location center, ExplosiveKind kind) {
    if (center == null || center.getWorld() == null) return;
    long now = System.currentTimeMillis();
    recentExplosions.removeIf(t -> now - t.timeMs > EXPLOSION_TAG_MS);
    recentExplosions.add(new ExplosionTag(center.clone(), now, kind));
  }

  private boolean isNearExplosionTag(Player player, ExplosiveKind kind) {
    if (player == null || !player.isOnline()) return false;
    long now = System.currentTimeMillis();
    World world = player.getWorld();
    Location pl = player.getLocation();
    double r2 = EXPLOSION_TAG_RADIUS * EXPLOSION_TAG_RADIUS;
    for (ExplosionTag t : recentExplosions) {
      if (t.kind != kind) continue;
      if (now - t.timeMs > EXPLOSION_TAG_MS) continue;
      Location c = t.center;
      if (c.getWorld() == null || !c.getWorld().equals(world)) continue;
      if (pl.distanceSquared(c) <= r2) {
        return true;
      }
    }
    return false;
  }

  private static void denyInteract(PlayerInteractEvent event) {
    event.setCancelled(true);
    event.setUseItemInHand(Event.Result.DENY);
    event.setUseInteractedBlock(Event.Result.DENY);
  }

  private boolean master() {
    return plugin.getConfig().getBoolean("explosive_controls.enabled", false);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onCrystalPlace(BlockPlaceEvent event) {
    if (!master()) return;
    if (event.getBlock().getType() != Material.END_CRYSTAL) return;
    if (!plugin.getConfig().getBoolean("explosive_controls.end_crystal.prevent_placement", false)) return;

    event.setCancelled(true);
    notify(event.getPlayer(), "explosive_denied_end_crystal");
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onBedNetherEnd(PlayerInteractEvent event) {
    if (!master()) return;
    if (!plugin.getConfig().getBoolean("explosive_controls.bed_nether_end.prevent_use", false)) return;
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    Block block = event.getClickedBlock();
    if (block == null || !Tag.BEDS.isTagged(block.getType())) return;

    World.Environment env = block.getWorld().getEnvironment();
    if (env != World.Environment.NETHER && env != World.Environment.THE_END) return;

    denyInteract(event);
    notify(event.getPlayer(), "explosive_denied_bed");
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onRespawnAnchor(PlayerInteractEvent event) {
    if (!master()) return;
    if (!plugin.getConfig().getBoolean("explosive_controls.respawn_anchor.prevent_use", false)) return;
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    Block block = event.getClickedBlock();
    if (block == null || block.getType() != Material.RESPAWN_ANCHOR) return;

    denyInteract(event);
    notify(event.getPlayer(), "explosive_denied_anchor");
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onMinecartPrime(ExplosionPrimeEvent event) {
    if (!master()) return;
    if (!plugin.getConfig().getBoolean("explosive_controls.tnt_minecart.prevent_explosion", false)) return;
    if (event.getEntity().getType() != EntityType.TNT_MINECART) return;

    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    if (!master()) return;
    Entity e = event.getEntity();

    if (e instanceof EnderCrystal) {
      if (!plugin.getConfig().getBoolean("explosive_controls.end_crystal.explosion_break_blocks", true)) {
        event.blockList().clear();
      }
      return;
    }

    if (e.getType() == EntityType.TNT_MINECART) {
      if (!plugin.getConfig().getBoolean("explosive_controls.tnt_minecart.explosion_break_blocks", true)) {
        event.blockList().clear();
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    if (!master()) return;
    Block b = event.getBlock();
    Material type = b.getType();

    if (type == Material.RESPAWN_ANCHOR) {
      boolean stripDamage = !plugin.getConfig().getBoolean(
          "explosive_controls.respawn_anchor.explosion_damage_entities", true);
      boolean preventUse = plugin.getConfig().getBoolean(
          "explosive_controls.respawn_anchor.prevent_use", false);
      if (stripDamage || preventUse) {
        registerExplosionTag(b.getLocation().add(0.5, 0.5, 0.5), ExplosiveKind.RESPAWN_ANCHOR);
      }
      if (!plugin.getConfig().getBoolean("explosive_controls.respawn_anchor.explosion_break_blocks", true)) {
        event.blockList().clear();
      }
      return;
    }

    if (Tag.BEDS.isTagged(type)) {
      World.Environment env = b.getWorld().getEnvironment();
      if (env == World.Environment.NETHER || env == World.Environment.THE_END) {
        boolean stripDamage = !plugin.getConfig().getBoolean(
            "explosive_controls.bed_nether_end.explosion_damage_entities", true);
        boolean preventUse = plugin.getConfig().getBoolean(
            "explosive_controls.bed_nether_end.prevent_use", false);
        if (stripDamage || preventUse) {
          registerExplosionTag(b.getLocation().add(0.5, 0.5, 0.5), ExplosiveKind.BED_NETHER_END);
        }
      }
      if (!plugin.getConfig().getBoolean("explosive_controls.bed_nether_end.explosion_break_blocks", true)) {
        event.blockList().clear();
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onExplosionDamage(EntityDamageEvent event) {
    if (!master()) return;
    if (!(event.getEntity() instanceof Player player)) return;

    EntityDamageEvent.DamageCause cause = event.getCause();
    if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
        && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
      return;
    }

    DamageSource source = event.getDamageSource();
    Entity direct = source != null ? source.getDirectEntity() : null;

    if (direct instanceof EnderCrystal) {
      if (!plugin.getConfig().getBoolean("explosive_controls.end_crystal.explosion_damage_entities", true)) {
        event.setCancelled(true);
      }
      return;
    }

    if (direct != null && direct.getType() == EntityType.TNT_MINECART) {
      if (!plugin.getConfig().getBoolean("explosive_controls.tnt_minecart.explosion_damage_entities", true)) {
        event.setCancelled(true);
      }
      return;
    }

    // Respawn anchor: block is already gone when damage fires — use recent explosion tag + legacy location
    boolean anchorStrip = !plugin.getConfig().getBoolean(
        "explosive_controls.respawn_anchor.explosion_damage_entities", true);
    boolean anchorPreventUse = plugin.getConfig().getBoolean(
        "explosive_controls.respawn_anchor.prevent_use", false);
    if (anchorStrip || anchorPreventUse) {
      if (isNearExplosionTag(player, ExplosiveKind.RESPAWN_ANCHOR)) {
        event.setCancelled(true);
        return;
      }
      if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && source != null) {
        Location loc = source.getDamageLocation();
        // Paper can supply a damage Location with a null world; getBlock() NPEs in that case.
        if (loc != null && loc.getWorld() != null
            && loc.getBlock().getType() == Material.RESPAWN_ANCHOR) {
          event.setCancelled(true);
          return;
        }
      }
    }

    // Bed in Nether / End: same issue as anchor (source block destroyed before damage)
    boolean bedStrip = !plugin.getConfig().getBoolean(
        "explosive_controls.bed_nether_end.explosion_damage_entities", true);
    boolean bedPreventUse = plugin.getConfig().getBoolean(
        "explosive_controls.bed_nether_end.prevent_use", false);
    if (bedStrip || bedPreventUse) {
      World.Environment env = player.getWorld().getEnvironment();
      if (env == World.Environment.NETHER || env == World.Environment.THE_END) {
        if (isNearExplosionTag(player, ExplosiveKind.BED_NETHER_END)) {
          event.setCancelled(true);
          return;
        }
      }
      if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && source != null) {
        Location loc = source.getDamageLocation();
        if (loc != null && loc.getWorld() != null && Tag.BEDS.isTagged(loc.getBlock().getType())) {
          World.Environment blastEnv = loc.getWorld().getEnvironment();
          if (blastEnv == World.Environment.NETHER || blastEnv == World.Environment.THE_END) {
            if (bedStrip || bedPreventUse) {
              event.setCancelled(true);
            }
          }
        }
      }
    }
  }

  private void notify(Player player, String key) {
    if (player == null) return;
    String bar = plugin.getLanguageManager().getActionBar(key, Map.of());
    if (bar != null) {
      player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(bar));
    } else {
      plugin.getMessageService().sendMessage(player, key);
    }
  }
}
