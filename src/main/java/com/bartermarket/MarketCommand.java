package com.bartermarket;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class MarketCommand implements CommandExecutor {
    private static final int PAGE_SIZE = 45;
    private final BarterMarketPlugin plugin;
    private final MarketService service;

    public MarketCommand(BarterMarketPlugin plugin, MarketService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (require(sender, "bartermarket.use")) openMarketplace((Player) sender, 0);
            return true;
        }
        String subcommand = args[0].toLowerCase();
        String permission = switch (subcommand) {
            case "sell" -> "bartermarket.sell";
            case "listings" -> "bartermarket.listings";
            case "mailbox", "claim" -> "bartermarket.mailbox";
            case "history" -> "bartermarket.history";
            case "undo" -> "bartermarket.undo";
            case "admin" -> "bartermarket.admin";
            default -> "bartermarket.use";
        };
        if (!require(sender, permission)) return true;
        Player player = (Player) sender;
        switch (subcommand) {
            case "sell" -> openEditor(player, new MarketViewHolder("editor", player.getUniqueId(), 0));
            case "listings" -> openOwnListings(player, 0);
            case "mailbox" -> openMailbox(player);
            case "claim" -> claimAll(player);
            case "undo" -> {
                if (service.undoLatestListing(player.getUniqueId())) {
                    player.sendMessage(ChatColor.GREEN + "Your most recent active listing was canceled; its items are in your mailbox.");
                } else {
                    player.sendMessage(ChatColor.RED + "No eligible active listing was found.");
                }
            }
            case "history" -> player.sendMessage(ChatColor.YELLOW + "Transaction history is retained in the SQLite transaction journal.");
            case "help" -> sendHelp(player);
            case "admin" -> admin(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    private boolean require(CommandSender sender, String permission) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be run by a player.");
            return false;
        }
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use that command.");
            return false;
        }
        return true;
    }

    public void openMarketplace(Player player, int page) {
        List<MarketListing> listings = service.getActiveListings();
        int pages = Math.max(1, (listings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        MarketViewHolder holder = new MarketViewHolder("marketplace", player.getUniqueId(), current);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + "Barter Market " + (current + 1) + "/" + pages);
        int start = current * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, listings.size()); index++) {
            MarketListing listing = listings.get(index);
            ItemStack icon = listing.getOffer().get(0).clone();
            ItemMeta meta = icon.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_AQUA + "Seller: " + listing.getSellerName());
            lore.add(ChatColor.DARK_GRAY + "Listing: " + listing.getId());
            lore.add(ChatColor.GREEN + "Offers:");
            listing.getOffer().forEach(item -> lore.add(ChatColor.AQUA + item.getType().name() + " x" + item.getAmount()));
            lore.add(ChatColor.GOLD + "Requests:");
            listing.getRequested().forEach(item -> lore.add(ChatColor.YELLOW + item.getType().name() + " x" + item.getAmount()));
            lore.add(ChatColor.YELLOW + "Click to review");
            meta.setLore(lore);
            icon.setItemMeta(meta);
            int slot = index - start;
            inventory.setItem(slot, icon);
            holder.getEntryIds().put(slot, listing.getId());
        }
        inventory.setItem(45, button(Material.ARROW, "Previous page"));
        inventory.setItem(47, button(Material.GOLD_INGOT, "Send a gift"));
        inventory.setItem(48, button(Material.WRITABLE_BOOK, "Your listings"));
        inventory.setItem(49, button(Material.BARREL, "Mailbox (" + service.getPendingDeliveries(player.getUniqueId()).size() + ")"));
        inventory.setItem(50, button(Material.ANVIL, "Create listing"));
        inventory.setItem(53, button(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    public void openEditor(Player player, MarketViewHolder holder) {
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "Create Barter Listing");
        for (int slot = 10; slot <= 16; slot++) inventory.setItem(slot, button(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Offer item"));
        for (int slot = 28; slot <= 34; slot++) inventory.setItem(slot, button(Material.YELLOW_STAINED_GLASS_PANE, "Requested item"));
        inventory.setItem(19, button(Material.LIME_DYE, "Select offer from your inventory"));
        inventory.setItem(37, button(Material.COMPASS, "Choose requested item"));
        inventory.setItem(38, button(Material.RED_DYE, "Decrease selected request"));
        inventory.setItem(39, button(Material.PAPER, "Select a request, then adjust quantity"));
        inventory.setItem(40, button(Material.LIME_DYE, "Increase selected request"));
        inventory.setItem(45, button(Material.BARRIER, "Cancel"));
        inventory.setItem(49, button(Material.EMERALD, "Publish listing"));
        inventory.setItem(53, button(Material.PAPER, "Select offered items from your inventory"));
        renderEditor(inventory, holder);
        player.openInventory(inventory);
    }

    public void renderEditor(Inventory inventory, MarketViewHolder holder) {
        for (int slot = 10; slot <= 16; slot++) inventory.setItem(slot, button(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Offer item"));
        for (int slot = 28; slot <= 34; slot++) inventory.setItem(slot, button(Material.YELLOW_STAINED_GLASS_PANE, "Requested item"));
        for (int index = 0; index < Math.min(7, holder.getOffers().size()); index++) inventory.setItem(10 + index, holder.getOffers().get(index).clone());
        for (int index = 0; index < Math.min(7, holder.getRequests().size()); index++) inventory.setItem(28 + index, holder.getRequests().get(index).clone());
        inventory.setItem(53, button(Material.PAPER, "Select offered items from your inventory"));
        boolean selectedRequestValid = holder.getSelectedRequest() >= 0
            && holder.getSelectedRequest() < holder.getRequests().size();
        ItemStack quantity = button(Material.PAPER, !selectedRequestValid
                ? "Choose a requested item first"
                : "Selected request: " + holder.getRequests().get(holder.getSelectedRequest()).getAmount());
        inventory.setItem(39, quantity);
    }

    public void openRequestPicker(Player player, MarketViewHolder draft, int page) {
        List<Material> materials = java.util.Arrays.stream(Material.values())
                .filter(material -> material.isItem() && !material.isAir()).toList();
        int pageSize = 45;
        int pages = Math.max(1, (materials.size() + pageSize - 1) / pageSize);
        int current = Math.max(0, Math.min(page, pages - 1));
        MarketViewHolder holder = new MarketViewHolder("request-picker", player.getUniqueId(), current, draft);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "Choose Requested Item " + (current + 1) + "/" + pages);
        int start = current * pageSize;
        for (int index = start; index < Math.min(start + pageSize, materials.size()); index++) {
            Material material = materials.get(index);
            int slot = index - start;
            inventory.setItem(slot, new ItemStack(material));
            holder.getEntryIds().put(slot, material.name());
        }
        inventory.setItem(45, button(Material.ARROW, "Previous page"));
        inventory.setItem(49, button(Material.BARRIER, "Back to listing editor"));
        inventory.setItem(53, button(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    public void openGiftRecipients(Player player, int page) {
        List<KnownPlayer> recipients = service.getKnownPlayers().stream()
                .filter(recipient -> !recipient.uuid().equals(player.getUniqueId())).toList();
        int pages = Math.max(1, (recipients.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        MarketViewHolder holder = new MarketViewHolder("gift-recipients", player.getUniqueId(), current);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Choose Gift Recipient " + (current + 1) + "/" + pages);
        int start = current * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, recipients.size()); index++) {
            KnownPlayer recipient = recipients.get(index);
            int slot = index - start;
            ItemStack icon = button(Material.PAPER, recipient.name());
            ItemMeta meta = icon.getItemMeta();
            meta.setLore(List.of(ChatColor.DARK_AQUA + (Bukkit.getPlayer(recipient.uuid()) == null ? "Offline" : "Online"),
                    ChatColor.DARK_GRAY + recipient.uuid().toString()));
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            holder.getEntryIds().put(slot, recipient.uuid().toString());
        }
        inventory.setItem(45, button(Material.ARROW, "Previous page"));
        inventory.setItem(49, button(Material.BARRIER, "Back to marketplace"));
        inventory.setItem(53, button(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    public void openGiftEditor(Player player, KnownPlayer recipient, MarketViewHolder draft) {
        MarketViewHolder holder = draft == null
                ? new MarketViewHolder("gift-editor", player.getUniqueId(), 0) : draft;
        holder.setRecipientName(recipient.name());
        holder.getEntryIds().put(-1, recipient.uuid().toString());
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Gift to " + recipient.name());
        renderGiftEditor(inventory, holder);
        player.openInventory(inventory);
    }

    public void renderGiftEditor(Inventory inventory, MarketViewHolder holder) {
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, null);
        for (int slot = 10; slot <= 34; slot++) inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, "Gift item slot"));
        for (int index = 0; index < Math.min(25, holder.getOffers().size()); index++) {
            inventory.setItem(10 + index, holder.getOffers().get(index).clone());
        }
        inventory.setItem(45, button(Material.BARRIER, "Cancel gift"));
        inventory.setItem(49, button(Material.LIME_CONCRETE, "Review gift"));
        inventory.setItem(53, button(Material.PAPER, "Add gift items from your inventory"));
    }

    public void openGiftConfirmation(Player player, MarketViewHolder draft) {
        MarketViewHolder holder = new MarketViewHolder("gift-confirmation", player.getUniqueId(), 0, draft);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Confirm Gift to " + draft.getRecipientName());
        for (int index = 0; index < Math.min(27, draft.getOffers().size()); index++) {
            inventory.setItem(9 + index, draft.getOffers().get(index).clone());
        }
        inventory.setItem(4, button(Material.PLAYER_HEAD, "Recipient: " + draft.getRecipientName()));
        inventory.setItem(45, button(Material.BARRIER, "Back to gift"));
        inventory.setItem(49, button(Material.LIME_CONCRETE, "Send gift"));
        player.openInventory(inventory);
    }

    public void openConfirmation(Player player, String listingId) {
        MarketListing listing = service.getActiveListings().stream().filter(candidate -> candidate.getId().equals(listingId)).findFirst().orElse(null);
        if (listing == null) {
            player.sendMessage(ChatColor.RED + "That listing is no longer available.");
            openMarketplace(player, 0);
            return;
        }
        MarketViewHolder holder = new MarketViewHolder("confirmation", player.getUniqueId(), 0);
        holder.getEntryIds().put(22, listingId);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + "Confirm Barter");
        ItemStack offerIcon = listing.getOffer().get(0).clone();
        ItemMeta offerMeta = offerIcon.getItemMeta();
        offerMeta.setLore(listing.getOffer().stream().map(item -> ChatColor.AQUA + item.getType().name() + " x" + item.getAmount()).toList());
        offerIcon.setItemMeta(offerMeta);
        ItemStack requestIcon = listing.getRequested().get(0).clone();
        ItemMeta requestMeta = requestIcon.getItemMeta();
        requestMeta.setLore(listing.getRequested().stream().map(item -> ChatColor.YELLOW + item.getType().name() + " x" + item.getAmount()).toList());
        requestIcon.setItemMeta(requestMeta);
        inventory.setItem(11, offerIcon);
        inventory.setItem(15, requestIcon);
        inventory.setItem(22, button(Material.PAPER, "Listing " + listingId + " by " + listing.getSellerName()));
        inventory.setItem(29, button(Material.LIME_CONCRETE, "Confirm Purchase"));
        inventory.setItem(33, button(Material.RED_CONCRETE, "Cancel"));
        player.openInventory(inventory);
    }

    public void openOwnListings(Player player, int page) {
        List<MarketListing> listings = service.getListingsForSeller(player.getUniqueId()).stream()
                .filter(listing -> "ACTIVE".equals(listing.getStatus())).toList();
        int pages = Math.max(1, (listings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        MarketViewHolder holder = new MarketViewHolder("own-listings", player.getUniqueId(), current);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Your Listings " + (current + 1) + "/" + pages);
        int start = current * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, listings.size()); index++) {
            MarketListing listing = listings.get(index);
            ItemStack icon = listing.getOffer().get(0).clone();
            ItemMeta meta = icon.getItemMeta();
            meta.setLore(List.of(ChatColor.GRAY + "ID: " + listing.getId(), ChatColor.YELLOW + "Click to cancel"));
            icon.setItemMeta(meta);
            int slot = index - start;
            inventory.setItem(slot, icon);
            holder.getEntryIds().put(slot, listing.getId());
        }
        inventory.setItem(45, button(Material.ARROW, "Previous page"));
        inventory.setItem(49, button(Material.BARREL, "Mailbox"));
        inventory.setItem(53, button(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    public void openMailbox(Player player) {
        List<DeliveryRecord> deliveries = service.getPendingDeliveries(player.getUniqueId());
        MarketViewHolder holder = new MarketViewHolder("mailbox", player.getUniqueId(), 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.BLUE + "Marketplace Mailbox");
        int slot = 0;
        for (DeliveryRecord delivery : deliveries) {
            for (ItemStack item : delivery.getItems()) {
                if (slot >= 45) break;
                ItemStack icon = item.clone();
                ItemMeta meta = icon.getItemMeta();
                meta.setLore(List.of(ChatColor.DARK_AQUA + "Delivery: " + delivery.getId(),
                            ChatColor.DARK_GRAY + "Transaction: " + delivery.getTransactionId(),
                            delivery.getSenderName().isBlank() ? ChatColor.YELLOW + "Marketplace delivery" : ChatColor.GOLD + "Gift from " + delivery.getSenderName(),
                            ChatColor.YELLOW + "Click to claim this delivery"));
                icon.setItemMeta(meta);
                inventory.setItem(slot, icon);
                holder.getEntryIds().put(slot, delivery.getId());
                slot++;
            }
        }
        inventory.setItem(49, button(Material.HOPPER, "Claim all pending deliveries"));
        inventory.setItem(53, button(Material.ARROW, "Refresh"));
        player.openInventory(inventory);
    }

    public void openEmailInbox(Player player, int page) {
        List<MailMessage> messages = service.getEmails(player.getUniqueId());
        int pages = Math.max(1, (messages.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        MarketViewHolder holder = new MarketViewHolder("email-inbox", player.getUniqueId(), current);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                ChatColor.DARK_AQUA + "Email Inbox " + (current + 1) + "/" + pages);
        int start = current * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, messages.size()); index++) {
            MailMessage message = messages.get(index);
            int slot = index - start;
            ItemStack icon = button(message.isRead() ? Material.WRITABLE_BOOK : Material.BOOK,
                    (message.isRead() ? ChatColor.DARK_AQUA : ChatColor.GOLD) + "From " + message.senderName());
            ItemMeta meta = icon.getItemMeta();
            meta.setLore(List.of(ChatColor.DARK_GRAY + java.time.Instant.ofEpochMilli(message.createdAt()).toString(),
                    ChatColor.AQUA + preview(message.body()),
                    message.isRead() ? ChatColor.GREEN + "Read" : ChatColor.YELLOW + "Unread",
                    ChatColor.YELLOW + "Click to read"));
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            holder.getEntryIds().put(slot, message.id());
        }
        inventory.setItem(45, button(Material.ARROW, "Previous page"));
        inventory.setItem(49, button(Material.BARRIER, "Close"));
        inventory.setItem(53, button(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    public void openEmail(Player player, String messageId, int returnPage) {
        MailMessage message = service.getEmail(player.getUniqueId(), messageId);
        if (message == null) {
            player.sendMessage(ChatColor.RED + "That email is unavailable.");
            openEmailInbox(player, returnPage);
            return;
        }
        service.markEmailRead(player.getUniqueId(), messageId);
        MarketViewHolder holder = new MarketViewHolder("email-read", player.getUniqueId(), returnPage);
        holder.getEntryIds().put(0, messageId);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "Email from " + message.senderName());
        ItemStack letter = button(Material.WRITABLE_BOOK, ChatColor.GOLD + "From: " + message.senderName());
        ItemMeta meta = letter.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_AQUA + "To: " + message.recipientName());
        lore.add(ChatColor.DARK_GRAY + java.time.Instant.ofEpochMilli(message.createdAt()).toString());
        lore.add(ChatColor.GRAY + " ");
        lore.addAll(wrapEmailBody(message.body()));
        meta.setLore(lore);
        letter.setItemMeta(meta);
        inventory.setItem(22, letter);
        inventory.setItem(49, button(Material.ARROW, "Back to inbox"));
        player.openInventory(inventory);
    }

    private String preview(String body) {
        String firstLine = body.lines().findFirst().orElse("");
        return firstLine.length() > 35 ? firstLine.substring(0, 35) + "..." : firstLine;
    }

    private List<String> wrapEmailBody(String body) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : org.bukkit.ChatColor.stripColor(body).split("\\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                if (line.length() > 0 && line.length() + word.length() + 1 > 42) {
                    lines.add(ChatColor.WHITE + line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            lines.add(ChatColor.WHITE + line.toString());
        }
        return lines;
    }

    public void claimAll(Player player) {
        int claimed = 0;
        for (DeliveryRecord delivery : service.getPendingDeliveries(player.getUniqueId())) {
            if (service.claimDelivery(player, delivery.getId())) claimed++;
        }
        player.sendMessage(claimed == 0 ? ChatColor.YELLOW + "No deliveries could be claimed; check free inventory space."
                : ChatColor.GREEN + "Claimed " + claimed + " delivery record(s).");
    }

    public void sendHelp(Player player) {
        player.sendMessage(ChatColor.GREEN + "BarterMarket commands:");
        player.sendMessage(ChatColor.YELLOW + "/market - browse listings");
        player.sendMessage(ChatColor.YELLOW + "/market sell - create a listing from your inventory");
        player.sendMessage(ChatColor.YELLOW + "/market listings - manage your active listings");
        player.sendMessage(ChatColor.YELLOW + "/market mailbox - view pending deliveries");
        player.sendMessage(ChatColor.YELLOW + "/market claim - claim pending deliveries");
        player.sendMessage(ChatColor.YELLOW + "/market undo - cancel your most recent active listing");
        player.sendMessage(ChatColor.YELLOW + "/market history - show transaction journal availability");
        player.sendMessage(ChatColor.YELLOW + "/market help - show this help");
    }

    private void admin(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.AQUA + "/market admin validate | backup | export");
            player.sendMessage(ChatColor.YELLOW + "Interrupted transactions are quarantined for manual review.");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "validate" -> player.sendMessage(ChatColor.GREEN + "SQLite records are validated on load; malformed payloads prevent startup.");
            case "inspect" -> player.sendMessage(ChatColor.YELLOW + "Inspect is not yet exposed in this test build.");
            case "recover" -> player.sendMessage(ChatColor.RED + "Automatic grant recovery is intentionally disabled for ambiguous transactions.");
            default -> player.sendMessage(ChatColor.YELLOW + "Admin operation is not available in this test build.");
        }
    }

    private ItemStack button(Material material, String title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + title);
        item.setItemMeta(meta);
        return item;
    }
}
