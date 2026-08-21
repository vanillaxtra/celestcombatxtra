package com.shyamstudio.celestcombatXtra.listeners;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import io.canvasmc.canvas.event.EntityTeleportAsyncEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Canvas counterpart of {@link TeleportGraceListener}, mirroring {@link
 * CanvasTeleportListener}'s EntityTeleportAsyncEvent handling for the
 * {@code pvp.enabled: false} fallback grace path. Only ever
 * constructed/registered when {@code Scheduler.isRunningOnCanvas()} is true.
 */
public class CanvasTeleportGraceListener implements Listener {

    private final CelestCombatPro plugin;

    public CanvasTeleportGraceListener(CelestCombatPro plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTeleportAsync(EntityTeleportAsyncEvent event) {
        if (!plugin.getConfig().getBoolean("pvp.teleport.rearm_enabled", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!PvpToggleListener.isRearmQualifyingCause(event.getCause())) {
            return;
        }

        plugin.getTeleportGraceManager().grantGrace(player);
    }
}
