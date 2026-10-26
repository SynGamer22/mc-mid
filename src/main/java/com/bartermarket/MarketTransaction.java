package com.bartermarket;

import java.util.UUID;

public class MarketTransaction {
    private final String id;
    private final String listingId;
    private final UUID buyerUuid;
    private final UUID sellerUuid;
    private String state;

    public MarketTransaction(String id, String listingId, UUID buyerUuid, UUID sellerUuid, String state) {
        this.id = id;
        this.listingId = listingId;
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.state = state;
    }

    public String getId() {
        return id;
    }

    public String getListingId() {
        return listingId;
    }

    public UUID getBuyerUuid() {
        return buyerUuid;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
