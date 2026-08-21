package com.shyamstudio.celestcombatXtra.pvp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.shyamstudio.celestcombatXtra.language.MessageService;
import com.shyamstudio.celestcombatXtra.storage.PvpStorage;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the per-player PVP toggle state machine, its Caffeine-backed online-player
 * cache, and orchestration of the async storage layer.
 *
 * State is kept in a Caffeine cache keyed by player UUID rather than a plain map:
 * this is the "hit-time" cache the damage listeners read from (no DB access per
 * swing), and it is explicitly invalidated on quit (see {@link #onQuit(Player)}).
 * A player with no cache entry (not yet loaded, or offline) is always treated as
 * PVP-off - see {@link #isEffectivelyPvpEnabled(Player)}.
 */
public class PvpToggleManager {

    private final CelestCombatPro plugin;
    private final PvpStorage storage;
    private final MessageService messageService;

    private final Cache<UUID, PvpState> stateCache = Caffeine.newBuilder().build();
    private final Map<UUID, PvpWarmupTask> activeWarmups = new ConcurrentHashMap<>();

    public PvpToggleManager(CelestCombatPro plugin, PvpStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.messageService = plugin.getMessageService();
    }

    // ------------------------------------------------------------------
    // Join / quit
    // ------------------------------------------------------------------

    /**
     * Kicks off an async DB load for this player. Must never block the caller
     * (called from PlayerJoinEvent). Until the load completes, the player is
     * treated as PVP-off (no cache entry -> effectively disabled).
     */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        boolean defaultStatus = plugin.getConfig().getBoolean("pvp.default_status", true);

        Scheduler.runTaskAsync(() -> storage.loadPvpState(uuid).thenAccept(stored -> {
            boolean enabled = stored != null ? stored : defaultStatus;
            Scheduler.runTask(() -> {
                if (player.isOnline()) {
                    stateCache.put(uuid, enabled ? PvpState.ON : PvpState.OFF);
                }
            });
        }));
    }

    /**
     * Cancels any in-progress warmup and invalidates the cache entry. Safe to call
     * twice (e.g. from both PlayerQuitEvent and PlayerKickEvent).
     */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        PvpWarmupTask warmup = activeWarmups.remove(uuid);
        if (warmup != null) {
            warmup.cancel();
        }
        stateCache.invalidate(uuid);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public PvpState getState(Player player) {
        PvpState state = stateCache.getIfPresent(player.getUniqueId());
        return state != null ? state : PvpState.OFF;
    }

    /**
     * Effective PVP state for damage-check purposes. Not-yet-loaded players
     * (no cache entry) are treated as off, per the safe-default requirement.
     */
    public boolean isEffectivelyPvpEnabled(Player player) {
        PvpState state = stateCache.getIfPresent(player.getUniqueId());
        return state != null && state.isEffectivelyEnabled();
    }

    public boolean hasActiveWarmup(Player player) {
        return activeWarmups.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Command-driven toggle
    // ------------------------------------------------------------------

    public void togglePvp(Player player) {
        PvpState state = getState(player);
        switch (state) {
            case OFF -> startActivationWarmup(player, false);
            case ACTIVATION_WARMUP -> cancelActivationManually(player);
            case ON -> startDeactivationWarmup(player);
            case DEACTIVATION_WARMUP -> cancelDeactivationManually(player);
        }
    }

    private void startActivationWarmup(Player player, boolean teleportTriggered) {
        UUID uuid = player.getUniqueId();
        cancelExistingWarmup(uuid);

        long durationTicks = plugin.getTimeFromConfig("pvp.activation_warmup", "5s");
        stateCache.put(uuid, PvpState.ACTIVATION_WARMUP);
        sendWarmupMessage(player, "pvp_activation_warmup_start", durationTicks);

        Scheduler.Task task = Scheduler.runTaskLater(() -> completeActivation(player), durationTicks);
        activeWarmups.put(uuid, new PvpWarmupTask(task, PvpState.ON, teleportTriggered, durationTicks));
    }

    private void completeActivation(Player player) {
        UUID uuid = player.getUniqueId();
        activeWarmups.remove(uuid);
        stateCache.put(uuid, PvpState.ON);
        storage.savePvpState(uuid, true);
        if (player.isOnline()) {
            messageService.sendMessage(player, "pvp_activation_complete", new HashMap<>());
        }
    }

    private void cancelActivationManually(Player player) {
        UUID uuid = player.getUniqueId();
        cancelExistingWarmup(uuid);
        stateCache.put(uuid, PvpState.OFF);
        messageService.sendMessage(player, "pvp_activation_cancelled", new HashMap<>());
    }

    private void startDeactivationWarmup(Player player) {
        UUID uuid = player.getUniqueId();
        cancelExistingWarmup(uuid);

        long durationTicks = plugin.getTimeFromConfig("pvp.deactivation_warmup", "10s");
        stateCache.put(uuid, PvpState.DEACTIVATION_WARMUP);
        sendWarmupMessage(player, "pvp_deactivation_warmup_start", durationTicks);

        Scheduler.Task task = Scheduler.runTaskLater(() -> completeDeactivation(player), durationTicks);
        activeWarmups.put(uuid, new PvpWarmupTask(task, PvpState.OFF, false, durationTicks));
    }

    private void completeDeactivation(Player player) {
        UUID uuid = player.getUniqueId();
        activeWarmups.remove(uuid);
        stateCache.put(uuid, PvpState.OFF);
        storage.savePvpState(uuid, false);
        if (player.isOnline()) {
            messageService.sendMessage(player, "pvp_deactivation_complete", new HashMap<>());
        }
    }

    private void cancelDeactivationManually(Player player) {
        UUID uuid = player.getUniqueId();
        cancelExistingWarmup(uuid);
        stateCache.put(uuid, PvpState.ON);
        messageService.sendMessage(player, "pvp_deactivation_cancelled_manual", new HashMap<>());
    }

    private void cancelExistingWarmup(UUID uuid) {
        PvpWarmupTask existing = activeWarmups.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }
    }

    private void sendWarmupMessage(Player player, String key, long durationTicks) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("seconds", String.valueOf(durationTicks / 20));
        messageService.sendMessage(player, key, placeholders);
    }

    // ------------------------------------------------------------------
    // Combat tag hook - cancels an in-progress deactivation
    // ------------------------------------------------------------------

    public void onCombatTag(Player player) {
        UUID uuid = player.getUniqueId();
        if (getState(player) != PvpState.DEACTIVATION_WARMUP) {
            return;
        }
        cancelExistingWarmup(uuid);
        stateCache.put(uuid, PvpState.ON);
        messageService.sendMessage(player, "pvp_deactivation_cancelled_combat", new HashMap<>());
    }

    // ------------------------------------------------------------------
    // Teleport re-arm
    // ------------------------------------------------------------------

    /**
     * Called by the teleport listener when a qualifying teleport occurs while the
     * player's PVP is ON (or already mid re-arm, in which case the warmup simply
     * restarts). No-op if the player is OFF or mid-deactivation - see the plan's
     * edge-case decisions for why deactivation warmups are left untouched by teleports.
     */
    public void triggerTeleportRearm(Player player) {
        PvpState state = getState(player);
        if (state != PvpState.ON && !(state == PvpState.ACTIVATION_WARMUP && isTeleportTriggered(player))) {
            return;
        }
        startActivationWarmup(player, true);
        Map<String, String> placeholders = new HashMap<>();
        long durationTicks = plugin.getTimeFromConfig("pvp.activation_warmup", "5s");
        placeholders.put("seconds", String.valueOf(durationTicks / 20));
        messageService.sendMessage(player, "pvp_teleport_rearm_notice", placeholders);
    }

    private boolean isTeleportTriggered(Player player) {
        PvpWarmupTask warmup = activeWarmups.get(player.getUniqueId());
        return warmup != null && warmup.isTeleportTriggered();
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    public void shutdown() {
        activeWarmups.values().forEach(PvpWarmupTask::cancel);
        activeWarmups.clear();
        stateCache.invalidateAll();
    }
}
