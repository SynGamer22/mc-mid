package com.bartermarket;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GiftCommand implements CommandExecutor {
    private final MarketCommand marketCommand;

    public GiftCommand(MarketCommand marketCommand) {
        this.marketCommand = marketCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }
        if (!player.hasPermission("bartermarket.gift")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to send marketplace gifts.");
            return true;
        }
        marketCommand.openGiftRecipients(player, 0);
        return true;
    }
}
