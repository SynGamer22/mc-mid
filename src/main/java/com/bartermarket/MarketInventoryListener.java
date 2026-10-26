package com.bartermarket;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class MarketInventoryListener implements Listener {
    private static final OfferSelection.StackAccess<ItemStack> ITEM_STACK_ACCESS = new OfferSelection.StackAccess<>() {
        @Override
        public boolean valid(ItemStack stack) {
            return !stack.getType().isAir() && stack.getAmount() > 0;
        }

        @Override
        public boolean similar(ItemStack first, ItemStack second) {
            return first.isSimilar(second);
        }

        @Override
        public int amount(ItemStack stack) {
            return stack.getAmount();
        }

        @Override
        public int maxStackSize(ItemStack stack) {
            return stack.getMaxStackSize();
        }

        @Override
        public ItemStack withAmount(ItemStack stack, int amount) {
            ItemStack replacement = stack.clone();
            replacement.setAmount(amount);
            return replacement;
        }
    };

    private final BarterMarketPlugin plugin;
    private final MarketService service;

    public MarketInventoryListener(BarterMarketPlugin plugin, MarketService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MarketViewHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || holder.getOwner() == null
                || !holder.getOwner().equals(player.getUniqueId())) return;

        if (holder.getId().equals("editor") && event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
            ItemStack selected = event.getCurrentItem();
            if (selected != null && !selected.getType().isAir()) {
                ItemStack chosen = selected.clone();
                if (event.isRightClick()) chosen.setAmount(1);
                OfferSelection.Result result = OfferSelection.add(holder.getOffers(), chosen, 7, ITEM_STACK_ACCESS);
                if (result == OfferSelection.Result.DIFFERENT_ITEM) {
                    player.sendMessage(ChatColor.RED + "An offer can contain only one item type. Remove its current item before choosing another.");
                } else if (result == OfferSelection.Result.CAPACITY_EXCEEDED) {
                    player.sendMessage(ChatColor.RED + "That amount exceeds the remaining offer slots.");
                }
                plugin.getMarketCommand().renderEditor(top, holder);
            }
            return;
        }

        if (holder.getId().equals("gift-editor") && event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
            ItemStack selected = event.getCurrentItem();
            if (selected != null && !selected.getType().isAir()) {
                ItemStack chosen = selected.clone();
                if (event.isRightClick()) chosen.setAmount(1);
                if (!addGiftItem(holder.getOffers(), chosen, 25)) {
                    player.sendMessage(ChatColor.RED + "The gift exceeds the available gift slots.");
                }
                plugin.getMarketCommand().renderGiftEditor(top, holder);
            }
            return;
        }

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        int slot = event.getRawSlot();
        switch (holder.getId()) {
            case "marketplace" -> marketplaceClick(player, holder, slot);
            case "confirmation" -> confirmationClick(player, holder, slot);
            case "editor" -> editorClick(player, holder, top, slot);
            case "request-picker" -> requestPickerClick(player, holder, slot);
            case "gift-recipients" -> giftRecipientClick(player, holder, slot);
            case "gift-editor" -> giftEditorClick(player, holder, top, slot);
            case "gift-confirmation" -> giftConfirmationClick(player, holder, slot);
            case "email-inbox" -> emailInboxClick(player, holder, slot);
            case "email-read" -> emailReadClick(player, holder, slot);
            case "own-listings" -> ownListingsClick(player, holder, slot);
            case "mailbox" -> mailboxClick(player, holder, slot);
            default -> player.closeInventory();
        }
    }

    private void marketplaceClick(Player player, MarketViewHolder holder, int slot) {
        String listingId = holder.getEntryIds().get(slot);
        if (listingId != null) {
            plugin.getMarketCommand().openConfirmation(player, listingId);
            return;
        }
        switch (slot) {
            case 45 -> plugin.getMarketCommand().openMarketplace(player, holder.getPage() - 1);
            case 47 -> plugin.getMarketCommand().openGiftRecipients(player, 0);
            case 48 -> plugin.getMarketCommand().openOwnListings(player, 0);
            case 49 -> plugin.getMarketCommand().openMailbox(player);
            case 50 -> plugin.getMarketCommand().openEditor(player, new MarketViewHolder("editor", player.getUniqueId(), 0));
            case 53 -> plugin.getMarketCommand().openMarketplace(player, holder.getPage() + 1);
            default -> { }
        }
    }

    private void confirmationClick(Player player, MarketViewHolder holder, int slot) {
        if (slot == 33) {
            plugin.getMarketCommand().openMarketplace(player, 0);
            return;
        }
        if (slot != 29) return;
        String listingId = holder.getEntryIds().get(22);
        if (listingId == null) return;
        if (service.purchaseListing(player, listingId)) {
            player.sendMessage(ChatColor.GREEN + "Purchase committed. Both item deliveries are in the mailboxes.");
            player.closeInventory();
        } else {
            player.sendMessage(ChatColor.RED + "Purchase failed: listing unavailable or requested items missing.");
            plugin.getMarketCommand().openMarketplace(player, 0);
        }
    }

    private void editorClick(Player player, MarketViewHolder holder, Inventory inventory, int slot) {
        if (slot == 19) {
            holder.setSelectionMode("offer");
        } else if (slot == 37) {
            plugin.getMarketCommand().openRequestPicker(player, holder, 0);
            return;
        } else if (slot >= 10 && slot <= 16) {
            if (slot - 10 < holder.getOffers().size()) holder.getOffers().clear();
        } else if (slot >= 28 && slot <= 34) {
            int index = slot - 28;
            if (index < holder.getRequests().size()) holder.setSelectedRequest(index);
        } else if (slot == 38) {
            adjustRequest(holder, -1);
        } else if (slot == 40) {
            adjustRequest(holder, 1);
        } else if (slot == 45) {
            player.closeInventory();
            return;
        } else if (slot == 49) {
            publishListing(player, holder);
            return;
        }
        plugin.getMarketCommand().renderEditor(inventory, holder);
    }

    private void publishListing(Player player, MarketViewHolder holder) {
        if (holder.getOffers().isEmpty()) {
            player.sendMessage(ChatColor.RED + "Add at least one offered item from your inventory first.");
            return;
        }
        if (holder.getRequests().isEmpty()) {
            player.sendMessage(ChatColor.RED + "Choose at least one requested item first.");
            return;
        }
        if (!player.hasPermission("bartermarket.sell")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to create listings.");
            return;
        }
        long expiry = plugin.getConfig().getLong("market.defaultListingExpiryMinutes", 1440L) * 60_000L;
        try {
            String id = service.createListing(player, holder.getOffers(), holder.getRequests(), expiry);
            if (id == null) {
                player.sendMessage(ChatColor.RED + "Listing was not published. Ensure you still have the selected offer items and have room under your active listing limit.");
                return;
            }
            player.sendMessage(Component.text(ChatColor.GREEN + "Your item has been listed! Listing ID: " + id + " [UNDO]")
                    .clickEvent(ClickEvent.runCommand("/market undo")));
            player.closeInventory();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Listing publication failed for " + player.getUniqueId()
                    + ": " + exception.getMessage());
            exception.printStackTrace();
                player.sendMessage(ChatColor.RED + "Listing publication failed and may need recovery. Do not retry or move related items; contact an administrator and provide the server log error.");
        }
    }

    private void requestPickerClick(Player player, MarketViewHolder holder, int slot) {
        MarketViewHolder draft = holder.getParent();
        if (draft == null) {
            player.closeInventory();
            return;
        }
        String materialName = holder.getEntryIds().get(slot);
        if (materialName != null) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName);
            if (material != null && material.isItem() && !material.isAir()) {
                addRequestedMaterial(draft, material);
            }
            plugin.getMarketCommand().openEditor(player, draft);
            return;
        }
        switch (slot) {
            case 45 -> plugin.getMarketCommand().openRequestPicker(player, draft, holder.getPage() - 1);
            case 49 -> plugin.getMarketCommand().openEditor(player, draft);
            case 53 -> plugin.getMarketCommand().openRequestPicker(player, draft, holder.getPage() + 1);
            default -> { }
        }
    }

    private void giftRecipientClick(Player player, MarketViewHolder holder, int slot) {
        String uuidValue = holder.getEntryIds().get(slot);
        if (uuidValue != null) {
            KnownPlayer recipient = service.getKnownPlayer(UUID.fromString(uuidValue));
            if (recipient == null) {
                player.sendMessage(ChatColor.RED + "That recipient is no longer available.");
                plugin.getMarketCommand().openGiftRecipients(player, holder.getPage());
                return;
            }
            plugin.getMarketCommand().openGiftEditor(player, recipient, null);
            return;
        }
        switch (slot) {
            case 45 -> plugin.getMarketCommand().openGiftRecipients(player, holder.getPage() - 1);
            case 49 -> plugin.getMarketCommand().openMarketplace(player, 0);
            case 53 -> plugin.getMarketCommand().openGiftRecipients(player, holder.getPage() + 1);
            default -> { }
        }
    }

    private void giftEditorClick(Player player, MarketViewHolder holder, Inventory inventory, int slot) {
        if (slot >= 10 && slot <= 34) {
            int index = slot - 10;
            if (index < holder.getOffers().size()) holder.getOffers().remove(index);
        } else if (slot == 45) {
            plugin.getMarketCommand().openMarketplace(player, 0);
            return;
        } else if (slot == 49) {
            if (holder.getOffers().isEmpty()) {
                player.sendMessage(ChatColor.RED + "Add at least one item to the gift first.");
                return;
            }
            plugin.getMarketCommand().openGiftConfirmation(player, holder);
            return;
        }
        plugin.getMarketCommand().renderGiftEditor(inventory, holder);
    }

    private void giftConfirmationClick(Player player, MarketViewHolder confirmation, int slot) {
        MarketViewHolder draft = confirmation.getParent();
        if (draft == null) {
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            KnownPlayer recipient = getRecipient(draft);
            if (recipient != null) plugin.getMarketCommand().openGiftEditor(player, recipient, draft);
            return;
        }
        if (slot != 49) return;
        KnownPlayer recipient = getRecipient(draft);
        if (recipient == null) {
            player.sendMessage(ChatColor.RED + "The selected recipient could not be found.");
            player.closeInventory();
            return;
        }
        try {
            String transactionId = service.createGift(player, recipient.uuid(), recipient.name(), draft.getOffers());
            if (transactionId == null) {
                player.sendMessage(ChatColor.RED + "Gift not sent. Check your gift permission and make sure you still have all selected items.");
                return;
            }
            player.sendMessage(ChatColor.GREEN + "Gift sent to " + recipient.name() + ". Delivery ID is recorded in the mailbox.");
            player.closeInventory();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Gift publication failed for " + player.getUniqueId() + ": " + exception.getMessage());
            exception.printStackTrace();
            player.sendMessage(ChatColor.RED + "Gift sending failed and may need recovery. Do not retry or move the selected items; contact an administrator.");
        }
    }

    private KnownPlayer getRecipient(MarketViewHolder draft) {
        String uuidValue = draft.getEntryIds().get(-1);
        return uuidValue == null ? null : service.getKnownPlayer(UUID.fromString(uuidValue));
    }

    private boolean addGiftItem(List<ItemStack> selectedItems, ItemStack selected, int slotLimit) {
        List<ItemStack> replacement = selectedItems.stream().map(ItemStack::clone)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int remaining = selected.getAmount();
        for (ItemStack existing : replacement) {
            if (!existing.isSimilar(selected) || existing.getAmount() >= existing.getMaxStackSize()) continue;
            int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            remaining -= moved;
            if (remaining == 0) break;
        }
        while (remaining > 0) {
            if (replacement.size() >= slotLimit) return false;
            ItemStack stack = selected.clone();
            int amount = Math.min(remaining, stack.getMaxStackSize());
            stack.setAmount(amount);
            replacement.add(stack);
            remaining -= amount;
        }
        selectedItems.clear();
        selectedItems.addAll(replacement);
        return true;
    }

    private void addRequestedMaterial(MarketViewHolder draft, org.bukkit.Material material) {
        List<ItemStack> requests = draft.getRequests();
        for (int index = 0; index < requests.size(); index++) {
            ItemStack request = requests.get(index);
            if (request.getType() == material && request.getAmount() < request.getMaxStackSize()) {
                request.setAmount(request.getAmount() + 1);
                draft.setSelectedRequest(index);
                return;
            }
        }
        if (requests.size() < 7) {
            requests.add(new ItemStack(material, 1));
            draft.setSelectedRequest(requests.size() - 1);
        }
    }

    private void adjustRequest(MarketViewHolder holder, int delta) {
        int index = holder.getSelectedRequest();
        if (index < 0 || index >= holder.getRequests().size()) return;
        ItemStack request = holder.getRequests().get(index);
        int adjusted = request.getAmount() + delta;
        if (adjusted <= 0) {
            holder.getRequests().remove(index);
            holder.setSelectedRequest(holder.getRequests().isEmpty() ? -1 : Math.min(index, holder.getRequests().size() - 1));
        } else if (adjusted <= request.getMaxStackSize()) {
            request.setAmount(adjusted);
        }
    }

    private void ownListingsClick(Player player, MarketViewHolder holder, int slot) {
        String listingId = holder.getEntryIds().get(slot);
        if (listingId != null) {
            if (service.cancelListing(player.getUniqueId(), listingId)) {
                player.sendMessage(ChatColor.GREEN + "Listing canceled. Its reserved items were sent to your mailbox.");
                plugin.getMarketCommand().openMailbox(player);
            } else {
                player.sendMessage(ChatColor.RED + "That listing is no longer cancelable.");
                player.closeInventory();
            }
            return;
        }
        switch (slot) {
            case 45 -> plugin.getMarketCommand().openOwnListings(player, holder.getPage() - 1);
            case 49 -> plugin.getMarketCommand().openMailbox(player);
            case 53 -> plugin.getMarketCommand().openOwnListings(player, holder.getPage() + 1);
            default -> { }
        }
    }

    private void mailboxClick(Player player, MarketViewHolder holder, int slot) {
        String deliveryId = holder.getEntryIds().get(slot);
        if (deliveryId != null) {
            if (service.claimDelivery(player, deliveryId)) {
                player.sendMessage(ChatColor.GREEN + "Delivery claimed.");
            } else {
                player.sendMessage(ChatColor.RED + "Delivery could not be claimed. Ensure your inventory has enough room.");
            }
            plugin.getMarketCommand().openMailbox(player);
            return;
        }
        if (slot == 49) plugin.getMarketCommand().claimAll(player);
        if (slot == 53) plugin.getMarketCommand().openMailbox(player);
    }

    private void emailInboxClick(Player player, MarketViewHolder holder, int slot) {
        String messageId = holder.getEntryIds().get(slot);
        if (messageId != null) {
            plugin.getMarketCommand().openEmail(player, messageId, holder.getPage());
            return;
        }
        switch (slot) {
            case 45 -> plugin.getMarketCommand().openEmailInbox(player, holder.getPage() - 1);
            case 49 -> player.closeInventory();
            case 53 -> plugin.getMarketCommand().openEmailInbox(player, holder.getPage() + 1);
            default -> { }
        }
    }

    private void emailReadClick(Player player, MarketViewHolder holder, int slot) {
        if (slot == 49) plugin.getMarketCommand().openEmailInbox(player, holder.getPage());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MarketViewHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof MarketViewHolder) {
            event.setCancelled(true);
        }
    }
}
