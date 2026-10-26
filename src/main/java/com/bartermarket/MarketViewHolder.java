package com.bartermarket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class MarketViewHolder implements InventoryHolder {
    private final String type;
    private final UUID owner;
    private final int page;
    private final MarketViewHolder parent;
    private String recipientName;
    private final Map<Integer, String> entryIds = new HashMap<>();
    private final List<ItemStack> offers = new ArrayList<>();
    private final List<ItemStack> requests = new ArrayList<>();
    private String selectionMode = "offer";
    private int selectedRequest = -1;

    public MarketViewHolder(String type) {
        this(type, null, 0);
    }

    public MarketViewHolder(String type, UUID owner, int page) {
        this(type, owner, page, null);
    }

    public MarketViewHolder(String type, UUID owner, int page, MarketViewHolder parent) {
        this.type = type;
        this.owner = owner;
        this.page = page;
        this.parent = parent;
    }

    public String getId() {
        return type;
    }

    public UUID getOwner() {
        return owner;
    }

    public int getPage() {
        return page;
    }

    public MarketViewHolder getParent() {
        return parent;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public Map<Integer, String> getEntryIds() {
        return entryIds;
    }

    public List<ItemStack> getOffers() {
        return offers;
    }

    public List<ItemStack> getRequests() {
        return requests;
    }

    public String getSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(String selectionMode) {
        this.selectionMode = selectionMode;
    }

    public int getSelectedRequest() {
        return selectedRequest;
    }

    public void setSelectedRequest(int selectedRequest) {
        this.selectedRequest = selectedRequest;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
