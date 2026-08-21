package com.shyamstudio.celestcombatXtra.listeners;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.pvp.PvpToggleManager;
import io.canvasmc.canvas.event.EntityTeleportAsyncEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Canvas-only: EntityTeleportAsyncEvent covers teleports Canvas resolves off
 * the main thread that PlayerTeleportEvent alone can miss under its region-based
 * threading. Mirrors the same command/plugin-only re-arm filter as
 * {@link PvpToggleListener#onPlayerTeleport} (see
 * {@link PvpToggleListener#isRearmQualifyingCause}).
 *
 * Only ever constructed/registered when {@code Scheduler.isRunningOnCanvas()} is
 * true - see {@link com.shyamstudio.celestcombatXtra.CelestCombatPro#onEnable()}.
 */
public class CanvasTeleportListener implements Listener {

    private final CelestCombatPro plugin;

    public CanvasTeleportListener(CelestCombatPro plugin) {
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

        PvpToggleManager manager = plugin.getPvpToggleManager();
        manager.triggerTeleportRearm(player);
    }
}
