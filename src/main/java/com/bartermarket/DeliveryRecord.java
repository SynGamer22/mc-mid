package com.bartermarket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class DeliveryRecord {
    private final String id;
    private final UUID recipientUuid;
    private final String transactionId;
    private final String senderName;
    private final List<ItemStack> items;
    private String status;

    public DeliveryRecord(String id, UUID recipientUuid, String transactionId, List<ItemStack> items) {
        this(id, recipientUuid, transactionId, items, "");
    }

    public DeliveryRecord(String id, UUID recipientUuid, String transactionId, List<ItemStack> items, String senderName) {
        this.id = id;
        this.recipientUuid = recipientUuid;
        this.transactionId = transactionId;
        this.items = new ArrayList<>(items);
        this.senderName = senderName == null ? "" : senderName;
        this.status = "PENDING";
    }

    public String getId() {
        return id;
    }

    public UUID getRecipientUuid() {
        return recipientUuid;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getSenderName() {
        return senderName;
    }

    public List<ItemStack> getItems() {
        return items;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
