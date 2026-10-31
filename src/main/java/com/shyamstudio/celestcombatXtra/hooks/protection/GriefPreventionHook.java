package com.shyamstudio.celestcombatXtra.hooks.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;

import java.util.function.Supplier;

/**
 * GriefPrevention-backed claim_protection. Barrier rendering lives in {@link ClaimProtectionHook}.
 */
public class GriefPreventionHook extends ClaimProtectionHook {

    // null = NONE mode, nobody bypasses
    private ClaimPermission requiredPermission;

    public GriefPreventionHook(CelestCombatPro plugin, CombatManager combatManager) {
        super(plugin, combatManager);
    }

    @Override
    protected void reloadBackendConfig() {
        this.requiredPermission = loadRequiredPermission();
    }

    private ClaimPermission loadRequiredPermission() {
        String permissionName = plugin.getConfig().getString("claim_protection.required_permission", "BUILD");

        try {
            switch (permissionName.toUpperCase()) {
                case "MANAGE":
                    return ClaimPermission.Manage;
                case "ACCESS":
                    return ClaimPermission.Access;
                case "EDIT":
                    return ClaimPermission.Edit;
                case "BUILD":
                    return ClaimPermission.Build;
                case "CONTAINER":
                    return ClaimPermission.Inventory;
                case "NONE":
                    return null;
                default:
                    plugin.getLogger().warning("Invalid claim permission '" + permissionName + "' in config. Using BUILD instead.");
                    plugin.getLogger().warning("Valid permissions are: MANAGE, ACCESS, EDIT, BUILD, CONTAINER, NONE");
                    return ClaimPermission.Build;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error loading claim permission '" + permissionName + "': " + e.getMessage());
            return ClaimPermission.Build;
        }
    }

    @Override
    protected boolean checkClaimProtection(Location location, Player player) {
        try {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
            if (claim == null) {
                return false;
            }

            if (requiredPermission == null) {
                return true;
            }

            Supplier<String> permissionResult = claim.checkPermission(player, requiredPermission, null);
            return permissionResult != null;
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking GriefPrevention claim: " + e.getMessage());
            return false;
        }
    }
}
