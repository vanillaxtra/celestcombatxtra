package com.shyamstudio.celestcombatXtra.hooks.protection;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;

import java.util.UUID;

/**
 * Lands-plugin-backed claim_protection - one of two selectable claim-system
 * backends (see claim_protection.backend in config.yml), the other being
 * {@link GriefPreventionHook}. All barrier rendering/push-back/cleanup is shared
 * in {@link ClaimProtectionHook}; this class only knows how to ask Lands whether
 * a player is blocked from a location.
 *
 * claim_protection.required_permission is interpreted here as an owner/trust
 * check rather than GriefPrevention's tiered ClaimPermission enum - Lands has
 * no direct equivalent of MANAGE/ACCESS/EDIT/BUILD/CONTAINER. Two values are
 * supported: "TRUSTED" (default - the claim owner and trusted members bypass
 * the barrier) and "NONE" (nobody bypasses, not even the owner).
 */
public class LandsHook extends ClaimProtectionHook {

    private final LandsIntegration landsIntegration;

    /** True for the "NONE" mode - nobody bypasses the barrier, not even the claim owner. */
    private boolean blockEveryone;

    public LandsHook(CelestCombatPro plugin, CombatManager combatManager) {
        super(plugin, combatManager);
        this.landsIntegration = LandsIntegration.of(plugin);
    }

    @Override
    protected void reloadBackendConfig() {
        String value = plugin.getConfig().getString("claim_protection.required_permission", "TRUSTED");
        switch (value.toUpperCase()) {
            case "NONE":
                blockEveryone = true;
                break;
            case "TRUSTED":
                blockEveryone = false;
                break;
            default:
                plugin.getLogger().warning("Invalid claim_protection.required_permission '" + value
                    + "' for the Lands backend. Using TRUSTED instead. Valid values: TRUSTED, NONE.");
                blockEveryone = false;
        }
        plugin.debug("Lands claim protection mode: "
            + (blockEveryone ? "NONE (nobody bypasses)" : "TRUSTED (owner/trusted bypass)"));
    }

    @Override
    protected boolean checkClaimProtection(Location location, Player player) {
        try {
            Area area = landsIntegration.getArea(location);
            if (area == null) {
                return false;
            }

            if (blockEveryone) {
                return true;
            }

            UUID uuid = player.getUniqueId();
            return !(uuid.equals(area.getOwnerUID()) || area.isTrusted(uuid));
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking Lands claim: " + e.getMessage());
            return false; // Default to not protected if there's an error
        }
    }
}
