package com.shyamstudio.celestcombatXtra.pvp;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.language.MessageService;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback teleport re-arm grace period used when {@code pvp.enabled} is
 * false, so {@link PvpToggleManager} doesn't exist to drive
 * ACTIVATION_WARMUP. Self-contained like NewbieProtectionManager: a plain
 * per-player expiration-timestamp map with lazy expiry on read, no
 * scheduled tasks needed since grace windows are short-lived.
 */
public class TeleportGraceManager {

    private final CelestCombatPro plugin;
    private final MessageService messageService;

    private final Map<UUID, Long> graceExpiresAt = new ConcurrentHashMap<>();

    public TeleportGraceManager(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.messageService = plugin.getMessageService();
    }

    public void grantGrace(Player player) {
        long durationTicks = plugin.getTimeFromConfig("pvp.activation_warmup", "5s");
        long durationMillis = durationTicks * 50L;
        graceExpiresAt.put(player.getUniqueId(), System.currentTimeMillis() + durationMillis);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("seconds", String.valueOf(durationTicks / 20));
        messageService.sendMessage(player, "pvp_teleport_rearm_notice", placeholders);
    }

    public boolean hasGrace(Player player) {
        UUID uuid = player.getUniqueId();
        Long expiresAt = graceExpiresAt.get(uuid);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiresAt) {
            graceExpiresAt.remove(uuid);
            return false;
        }
        return true;
    }

    public void clear(UUID uuid) {
        graceExpiresAt.remove(uuid);
    }
}
