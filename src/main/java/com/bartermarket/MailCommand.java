package com.bartermarket;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class MailCommand implements CommandExecutor, TabCompleter {
    private final BarterMarketPlugin plugin;
    private final MarketCommand marketCommand;
    private final MarketService service;

    public MailCommand(BarterMarketPlugin plugin, MarketCommand marketCommand, MarketService service) {
        this.plugin = plugin;
        this.marketCommand = marketCommand;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }
        if (args.length == 0) {
            if (!player.hasPermission("bartermarket.email")) {
                player.sendMessage(ChatColor.RED + "You do not have permission to use the email inbox.");
                return true;
            }
            marketCommand.openEmailInbox(player, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            player.sendMessage(ChatColor.AQUA + "Email: /mail opens your inbox; /mail <player> <message> sends mail.");
            player.sendMessage(ChatColor.GOLD + "Example: /mail Alex I'll meet you at spawn.");
            return true;
        }
        if (!player.hasPermission("bartermarket.email")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to send email.");
            return true;
        }

        int recipientIndex = args[0].equalsIgnoreCase("send") ? 1 : 0;
        int bodyIndex = recipientIndex + 1;
        if (args.length <= bodyIndex) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /mail <player> <message> or /mail send <player> <message>");
            return true;
        }
        String recipient = args[recipientIndex];
        String body = String.join(" ", Arrays.copyOfRange(args, bodyIndex, args.length));
        try {
            String messageId = service.sendEmail(player, recipient, body);
            if (messageId == null) {
                player.sendMessage(ChatColor.RED + "Email not sent. Check the recipient name, message length, and that you are not mailing yourself.");
            } else {
                player.sendMessage(ChatColor.GREEN + "Email sent to " + recipient + ". Message ID: " + messageId);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Email send failed for " + player.getUniqueId() + ": " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "Email could not be saved. Please try again later.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("bartermarket.email")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> names = service.getKnownPlayers().stream().map(KnownPlayer::name)
                    .filter(name -> !name.equalsIgnoreCase(player.getName()))
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(100).toList();
            if ("send".startsWith(prefix) && names.size() < 100) names = append(names, "send");
            return names;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("send")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return service.getKnownPlayers().stream().map(KnownPlayer::name)
                    .filter(name -> !name.equalsIgnoreCase(player.getName()))
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(100).toList();
        }
        return List.of();
    }

    private List<String> append(List<String> names, String value) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(names);
        result.add(value);
        return result;
    }
}
