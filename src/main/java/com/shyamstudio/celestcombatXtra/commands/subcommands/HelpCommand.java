package com.shyamstudio.celestcombatXtra.commands.subcommands;

import org.bukkit.command.CommandSender;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.commands.BaseCommand;

import java.util.ArrayList;
import java.util.List;

public class HelpCommand extends BaseCommand {

    public HelpCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }
        messageService.sendMessage(sender, "help_message");
        return true;
    }

    @Override
    public String getPermission() {
        return "celestcombatxtra.command.use";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // Help can be shown to console as well
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return new ArrayList<>(); // No tab completion for help command
    }
}