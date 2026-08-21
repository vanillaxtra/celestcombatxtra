package com.shyamstudio.celestcombatXtra.highlight;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PacketEvents is NOT shaded into this jar - it must be installed as a separate
 * server plugin (declared as a soft-depend in plugin.yml so it loads before us).
 * It bootstraps its own PacketEventsAPI instance in its own onLoad/onEnable; we
 * only ever read {@link PacketEvents#getAPI()}, never call setAPI/load/init/terminate
 * ourselves. PacketEvents is required only for the PVP status highlight (glow)
 * feature - if it isn't installed (or isn't initialized), that feature is silently
 * skipped and everything else in the plugin works normally.
 */
public final class PacketEventsBootstrap {
    private static boolean available = false;

    private PacketEventsBootstrap() {}

    /** Call once from JavaPlugin#onEnable(), after soft-deps have loaded. */
    public static void detect(JavaPlugin plugin) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                available = false;
                plugin.getLogger().info("PacketEvents plugin not found - the PVP status highlight "
                        + "feature is disabled. Install PacketEvents to enable it; everything else "
                        + "(toggle, warmups, damage gating) works normally without it.");
                return;
            }

            available = PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized();
            if (!available) {
                plugin.getLogger().warning("PacketEvents is installed but not initialized yet - "
                        + "the PVP status highlight feature is disabled.");
            }
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warning("Failed to detect PacketEvents - the PVP status highlight "
                    + "feature will be disabled, everything else works normally. Cause: " + t);
        }
    }

    /** True only when PacketEvents is installed, enabled, and initialized. */
    public static boolean isAvailable() {
        return available;
    }
}
