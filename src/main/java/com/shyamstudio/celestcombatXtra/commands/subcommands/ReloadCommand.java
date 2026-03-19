package com.shyamstudio.celestcombatXtra.commands.subcommands;

import org.bukkit.command.CommandSender;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.CelestCombatXtra;
import com.shyamstudio.celestcombatXtra.commands.BaseCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReloadCommand extends BaseCommand {

    public ReloadCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }

        Map<String, String> placeholders = new HashMap<>();

        plugin.reload();

        // Reload config
        plugin.reloadConfig();
        plugin.getLanguageManager().reloadLanguages();
        plugin.refreshTimeCache();

        if (plugin.getWorldGuardHook() != null) {
            plugin.getWorldGuardHook().reloadConfig();
        }
        if (plugin.getGriefPreventionHook() != null) {
            plugin.getGriefPreventionHook().reloadConfig();
        }

        // Reload combat manager configuration
        plugin.getCombatManager().reloadConfig();
        plugin.getKillRewardManager().loadConfig();
        plugin.getNewbieProtectionManager().reloadConfig();
        plugin.getCombatListeners().reload();

        if (plugin instanceof CelestCombatXtra xtra) {
            List<String> skippedReserved = xtra.reloadPhase1Listeners();
            if (!skippedReserved.isEmpty()) {
                Map<String, String> skipPh = new HashMap<>();
                skipPh.put("materials", String.join(", ", skippedReserved));
                messageService.sendMessage(sender, "config_reload_skipped_reserved_cooldown_items", skipPh);
            }
        }

        messageService.clearKeyExistsCache();

        // Send success message
        messageService.sendMessage(sender, "config_reloaded", placeholders);

        return true;
    }

    @Override
    public String getPermission() {
        return "celestcombatxtra.command.use";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // Allow console to reload the plugin
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return new ArrayList<>(); // No tab completion needed for reload
    }
}