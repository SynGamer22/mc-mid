package com.bartermarket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class MarketListing {
    private final String id;
    private final UUID sellerUuid;
    private final String sellerName;
    private final List<ItemStack> offer;
    private final List<ItemStack> requested;
    private final long createdAt;
    private final long expiresAt;
    private String status;

    public MarketListing(String id, UUID sellerUuid, String sellerName, List<ItemStack> offer,
                        List<ItemStack> requested, long createdAt, long expiresAt) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.offer = new ArrayList<>(offer);
        this.requested = new ArrayList<>(requested);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = "ACTIVE";
    }

    public String getId() {
        return id;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    public List<ItemStack> getOffer() {
        return offer;
    }

    public List<ItemStack> getRequested() {
        return requested;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
