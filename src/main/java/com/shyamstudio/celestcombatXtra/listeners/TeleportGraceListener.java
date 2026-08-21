package com.shyamstudio.celestcombatXtra.listeners;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.pvp.TeleportGraceManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Teleport re-arm grace, active only when {@code pvp.enabled} is false (see
 * {@link CelestCombatPro#onEnable()}) - {@link PvpToggleListener} already
 * covers the {@code pvp.enabled: true} case via {@link
 * com.shyamstudio.celestcombatXtra.pvp.PvpToggleManager}.
 */
public class TeleportGraceListener implements Listener {

    private final CelestCombatPro plugin;

    public TeleportGraceListener(CelestCombatPro plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfig().getBoolean("pvp.teleport.rearm_enabled", true)) {
            return;
        }
        if (!PvpToggleListener.isRearmQualifyingCause(event.getCause())) {
            return;
        }

        plugin.getTeleportGraceManager().grantGrace(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getTeleportGraceManager().clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        plugin.getTeleportGraceManager().clear(event.getPlayer().getUniqueId());
    }
}
