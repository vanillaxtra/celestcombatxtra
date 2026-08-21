package com.shyamstudio.celestcombatXtra.commands;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.language.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Top-level {@code /pvp} command - toggles the sender's own PVP state.
 * Registered directly on its own PluginCommand rather than routed through
 * CommandManager's subcommand map, since it is a standalone root command.
 */
public class PvpCommand implements CommandExecutor {

    private static final String PERMISSION = "celestcombatxtra.command.pvp";

    private final CelestCombatPro plugin;
    private final MessageService messageService;

    public PvpCommand(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.messageService = plugin.getMessageService();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messageService.sendMessage(sender, "error_player_only", new HashMap<>());
            return true;
        }

        if (!player.hasPermission(PERMISSION)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("permission", PERMISSION);
            messageService.sendMessage(player, "no_permission", placeholders);
            return true;
        }

        plugin.getPvpToggleManager().togglePvp(player);
        return true;
    }
}
