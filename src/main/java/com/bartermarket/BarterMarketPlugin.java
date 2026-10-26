package com.bartermarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class BarterMarketPlugin extends JavaPlugin implements Listener {
    private MarketDatabase database;
    private MarketService marketService;
    private MarketCommand marketCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        database = new MarketDatabase(this);
        marketService = new MarketService(database, getConfig().getInt("market.maxListingsPerPlayer", 20));
        marketService.setMailboxNotifier((recipientUuid, senderName) -> {
            Player recipient = Bukkit.getPlayer(recipientUuid);
            if (recipient == null) return;
            String message = senderName == null || senderName.isBlank()
                ? ChatColor.GREEN + "New items were added to your marketplace mailbox. Click to open it."
                : ChatColor.GOLD + "You received a marketplace gift from " + senderName + ". Click to open it.";
            recipient.sendMessage(Component.text(message).clickEvent(ClickEvent.runCommand("/market mailbox")));
        });
        marketService.setEmailNotifier((recipientUuid, senderName) -> {
            Player recipient = Bukkit.getPlayer(recipientUuid);
            if (recipient != null) {
                recipient.sendMessage(Component.text(ChatColor.AQUA + "New email from " + senderName
                        + ". Use /mail to read it.").clickEvent(ClickEvent.runCommand("/mail")));
            }
        });
        marketCommand = new MarketCommand(this, marketService);
        getCommand("market").setExecutor(marketCommand);
        getCommand("gift").setExecutor(new GiftCommand(marketCommand));
        MailCommand mailCommand = new MailCommand(this, marketCommand, marketService);
        getCommand("mail").setExecutor(mailCommand);
        getCommand("mail").setTabCompleter(mailCommand);
        Bukkit.getPluginManager().registerEvents(new MarketInventoryListener(this, marketService), this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, marketService::expireListings, 1200L, 1200L);
        getLogger().info("Recovery summary: " + marketService.getActiveListings().size() + " active listings, "
                + marketService.pendingDeliveryCount() + " pending deliveries.");
        getLogger().info("BarterMarket enabled against the configured Paper API target.");
    }

    @Override
    public void onDisable() {
        if (database != null) database.close();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        marketService.recordPlayer(player.getUniqueId(), player.getName());
        var pending = marketService.getPendingDeliveries(player.getUniqueId());
        if (!pending.isEmpty()) {
            String gifts = pending.stream().map(DeliveryRecord::getSenderName).filter(name -> !name.isBlank())
                    .distinct().collect(java.util.stream.Collectors.joining(", "));
            String message = ChatColor.GOLD + "You have " + pending.size() + " unclaimed marketplace mailbox item(s).";
            if (!gifts.isBlank()) message += ChatColor.YELLOW + " Gift sender(s): " + gifts + ".";
            message += ChatColor.AQUA + " Click to open /market mailbox.";
            player.sendMessage(Component.text(message).clickEvent(ClickEvent.runCommand("/market mailbox")));
        }
        int unreadEmails = marketService.countUnreadEmails(player.getUniqueId());
        if (unreadEmails > 0) {
            player.sendMessage(Component.text(ChatColor.AQUA + "You have " + unreadEmails
                    + " unread email message(s). Click to open /mail.")
                    .clickEvent(ClickEvent.runCommand("/mail")));
        }
    }

    public MarketDatabase getDatabase() {
        return database;
    }

    public MarketService getMarketService() {
        return marketService;
    }

    public MarketCommand getMarketCommand() {
        return marketCommand;
    }

    public void sendMessage(CommandSender sender, String key, Object... args) {
        String message = getConfig().getString("messages." + key, key);
        for (int index = 0; index < args.length; index++) {
            message = message.replace("{" + index + "}", String.valueOf(args[index]));
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
