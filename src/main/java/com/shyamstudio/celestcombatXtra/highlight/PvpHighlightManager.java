package com.shyamstudio.celestcombatXtra.highlight;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.pvp.PvpState;
import com.shyamstudio.celestcombatXtra.pvp.PvpToggleManager;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer PVP status highlight: solid red glow/team-color while a player's
 * PVP is fully ON, flashing orange while arming (ACTIVATION_WARMUP), flashing
 * red while disarming (DEACTIVATION_WARMUP - still effectively PVP-on, per
 * {@link PvpState#isEffectivelyEnabled()}, so it stays visible as a "still
 * fightable" signal until the deactivation actually completes). Only visible to
 * viewers who are themselves in one of these three states - players with PVP
 * fully off see nothing, per the feature spec.
 *
 * Sent via PacketEvents fake entity-metadata (glowing bit) + per-viewer fake
 * scoreboard teams (color only, never a real shared Team), so nothing here
 * touches real shared entity/team state.
 */
public class PvpHighlightManager {

    private static final byte GLOWING_BIT = 0x40;
    private static final String TEAM_PREFIX = "ccxpvp_";

    private final CelestCombatPro plugin;
    private final PvpToggleManager pvpToggleManager;

    private Scheduler.Task task;
    private boolean flashOn = false;

    // viewer UUID -> set of target UUIDs currently highlighted for that viewer
    private Map<UUID, Set<UUID>> lastVisiblePairs = new HashMap<>();
    // viewer UUID -> set of fake team names already CREATEd for that viewer (vs needing UPDATE)
    private final Map<UUID, Set<String>> createdTeams = new ConcurrentHashMap<>();

    public PvpHighlightManager(CelestCombatPro plugin, PvpToggleManager pvpToggleManager) {
        this.plugin = plugin;
        this.pvpToggleManager = pvpToggleManager;
    }

    public void start() {
        // flash_interval is a raw tick count (a plain number), not a duration string -
        // TimeFormatter only understands s/m/h/d/w/mo/y suffixes, not "t".
        long interval = Math.max(1L, plugin.getTimeFromConfig("pvp.status_highlight.flash_interval", "10"));
        task = Scheduler.runTaskTimer(this::tick, interval, interval);
    }

    /** Re-reads flash_interval from config and restarts the tick task. Call on /reload. */
    public void restart() {
        if (task != null) {
            task.cancel();
        }
        clearAllVisiblePairs();
        start();
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("pvp.status_highlight.enabled", true);
    }

    /** ON (solid red), ACTIVATION_WARMUP (flash orange) and DEACTIVATION_WARMUP
     * (flash red) are all "still effectively PVP-on" states - both as something
     * worth showing to others, and as a state that itself grants the right to see
     * other players' highlights. Plain OFF is excluded from both roles. */
    private boolean isHighlightState(PvpState state) {
        return state == PvpState.ON || state == PvpState.ACTIVATION_WARMUP || state == PvpState.DEACTIVATION_WARMUP;
    }

    private void tick() {
        if (!isEnabled()) {
            clearAllVisiblePairs();
            return;
        }

        flashOn = !flashOn;
        Map<UUID, Set<UUID>> currentVisible = new HashMap<>();

        for (Player target : Bukkit.getOnlinePlayers()) {
            PvpState targetState = pvpToggleManager.getState(target);
            if (!isHighlightState(targetState)) {
                continue;
            }

            boolean glow = targetState == PvpState.ON || flashOn;
            NamedTextColor color = targetState == PvpState.ACTIVATION_WARMUP ? NamedTextColor.GOLD : NamedTextColor.RED;

            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(target)) {
                    continue;
                }
                PvpState viewerState = pvpToggleManager.getState(viewer);
                if (!isHighlightState(viewerState)) {
                    continue;
                }

                currentVisible.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>()).add(target.getUniqueId());
                sendGlow(viewer, target, glow);
                sendTeamColor(viewer, target, color);
            }
        }

        // Clear highlights for pairs that were visible last tick but no longer qualify
        // (target/viewer lost eligibility, either quit, etc.)
        for (Map.Entry<UUID, Set<UUID>> entry : lastVisiblePairs.entrySet()) {
            UUID viewerId = entry.getKey();
            Player viewer = Bukkit.getPlayer(viewerId);
            Set<UUID> stillVisible = currentVisible.getOrDefault(viewerId, Collections.emptySet());
            for (UUID targetId : entry.getValue()) {
                if (stillVisible.contains(targetId)) {
                    continue;
                }
                if (viewer != null && viewer.isOnline()) {
                    clearHighlight(viewer, targetId);
                }
            }
        }

        // Drop bookkeeping for viewers who are no longer online at all
        createdTeams.keySet().removeIf(viewerId -> Bukkit.getPlayer(viewerId) == null);

        lastVisiblePairs = currentVisible;
    }

    private void sendGlow(Player viewer, Player target, boolean glow) {
        byte flags = glow ? GLOWING_BIT : 0;
        List<EntityData> data = Collections.singletonList(new EntityData(0, EntityDataTypes.BYTE, flags));
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(target.getEntityId(), data);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private void sendTeamColor(Player viewer, Player target, NamedTextColor color) {
        String teamName = teamName(target.getUniqueId());
        Set<String> viewerTeams = createdTeams.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());

        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                null, null, null,
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                color,
                WrapperPlayServerTeams.OptionData.NONE);

        WrapperPlayServerTeams.TeamMode mode = viewerTeams.contains(teamName)
                ? WrapperPlayServerTeams.TeamMode.UPDATE
                : WrapperPlayServerTeams.TeamMode.CREATE;

        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                teamName, mode, info, target.getName());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        viewerTeams.add(teamName);
    }

    private void clearHighlight(Player viewer, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.isOnline()) {
            sendGlow(viewer, target, false);
        }

        String teamName = teamName(targetId);
        Set<String> viewerTeams = createdTeams.get(viewer.getUniqueId());
        if (viewerTeams != null && viewerTeams.remove(teamName)) {
            WrapperPlayServerTeams removePacket = new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, removePacket);
        }
    }

    private void clearAllVisiblePairs() {
        for (Map.Entry<UUID, Set<UUID>> entry : lastVisiblePairs.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            for (UUID targetId : entry.getValue()) {
                clearHighlight(viewer, targetId);
            }
        }
        lastVisiblePairs = new HashMap<>();
    }

    private String teamName(UUID targetId) {
        return TEAM_PREFIX + targetId.toString().replace("-", "").substring(0, 12);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        clearAllVisiblePairs();
        createdTeams.clear();
    }
}
