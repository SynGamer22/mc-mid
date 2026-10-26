package com.bartermarket;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class MarketService {
    private final MarketDatabase database;
    private final int maxListingsPerPlayer;
    private final Map<String, MarketListing> listings = new ConcurrentHashMap<>();
    private final Map<String, DeliveryRecord> deliveries = new ConcurrentHashMap<>();
    private BiConsumer<UUID, String> mailboxNotifier = (recipient, sender) -> { };
    private BiConsumer<UUID, String> emailNotifier = (recipient, sender) -> { };

    public MarketService(MarketDatabase database) {
        this(database, 20);
    }

    public MarketService(MarketDatabase database, int maxListingsPerPlayer) {
        this.database = database;
        this.maxListingsPerPlayer = Math.max(1, maxListingsPerPlayer);
        recoverInterruptedOperations();
        loadListings();
        loadDeliveries();
    }

    private void recoverInterruptedOperations() {
        database.transaction(connection -> {
            int listingsQuarantined;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE listings SET status='QUARANTINED' WHERE status IN ('RESERVING','LOCKED','RETURN_PENDING')")) {
                listingsQuarantined = statement.executeUpdate();
            }
            int deliveriesQuarantined;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE deliveries SET status='QUARANTINED' WHERE status='CLAIMING'")) {
                deliveriesQuarantined = statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state='RECOVERY_REQUIRED', updated_at=? WHERE state IN ('LISTING_RESERVING','PREPARED','PAYMENT_DEBITED')")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.executeUpdate();
            }
            database.logAudit("STARTUP_RECOVERY", null, "", "quarantined listings=" + listingsQuarantined
                    + ", deliveries=" + deliveriesQuarantined);
            return null;
        });
    }

    private void loadListings() {
        try (PreparedStatement statement = database.getConnection().prepareStatement("SELECT * FROM listings");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                MarketListing listing = new MarketListing(result.getString("id"),
                        UUID.fromString(result.getString("seller_uuid")), result.getString("seller_name"),
                        decodeItems(result.getString("offer_payload")),
                        decodeItems(result.getString("request_payload")), result.getLong("created_at"),
                        result.getLong("expires_at"));
                listing.setStatus(result.getString("status"));
                listings.put(listing.getId(), listing);
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to load and validate listings", e);
        }
    }

    private void loadDeliveries() {
        try (PreparedStatement statement = database.getConnection().prepareStatement("SELECT * FROM deliveries");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                DeliveryRecord delivery = new DeliveryRecord(result.getString("id"),
                        UUID.fromString(result.getString("recipient_uuid")), result.getString("transaction_id"),
                    decodeItems(result.getString("payload")), result.getString("sender_name"));
                delivery.setStatus(result.getString("status"));
                deliveries.put(delivery.getId(), delivery);
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to load and validate deliveries", e);
        }
    }

    public List<MarketListing> getActiveListings() {
        long now = System.currentTimeMillis();
        return listings.values().stream()
                .filter(listing -> "ACTIVE".equals(listing.getStatus()) && listing.getExpiresAt() > now)
                .sorted(Comparator.comparingLong(MarketListing::getCreatedAt).reversed()).toList();
    }

    public List<MarketListing> getListingsForSeller(UUID sellerUuid) {
        return listings.values().stream().filter(listing -> listing.getSellerUuid().equals(sellerUuid))
                .sorted(Comparator.comparingLong(MarketListing::getCreatedAt).reversed()).toList();
    }

    public void recordPlayer(UUID uuid, String name) {
        database.recordPlayer(uuid, name);
    }

    public void setMailboxNotifier(BiConsumer<UUID, String> mailboxNotifier) {
        this.mailboxNotifier = mailboxNotifier == null ? (recipient, sender) -> { } : mailboxNotifier;
    }

    public void setEmailNotifier(BiConsumer<UUID, String> emailNotifier) {
        this.emailNotifier = emailNotifier == null ? (recipient, sender) -> { } : emailNotifier;
    }

    public List<KnownPlayer> getKnownPlayers() {
        List<KnownPlayer> players = new ArrayList<>();
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT player_uuid,player_name,last_seen FROM player_directory ORDER BY player_name COLLATE NOCASE");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                players.add(new KnownPlayer(UUID.fromString(result.getString("player_uuid")),
                        result.getString("player_name"), result.getLong("last_seen")));
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to load known players", e);
        }
        return players;
    }

    public KnownPlayer getKnownPlayer(UUID uuid) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT player_uuid,player_name,last_seen FROM player_directory WHERE player_uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new KnownPlayer(UUID.fromString(result.getString("player_uuid")),
                        result.getString("player_name"), result.getLong("last_seen"));
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to load known player", e);
        }
    }

    public KnownPlayer findKnownPlayer(String playerName) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT player_uuid,player_name,last_seen FROM player_directory WHERE player_name=? COLLATE NOCASE LIMIT 1")) {
            statement.setString(1, playerName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new KnownPlayer(UUID.fromString(result.getString("player_uuid")),
                        result.getString("player_name"), result.getLong("last_seen"));
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to resolve mail recipient", e);
        }
    }

    public String sendEmail(Player sender, String recipientName, String rawBody) {
        if (!sender.hasPermission("bartermarket.email") || recipientName == null || recipientName.isBlank()
                || rawBody == null || rawBody.isBlank()) return null;
        KnownPlayer recipient = findKnownPlayer(recipientName.trim());
        if (recipient == null || recipient.uuid().equals(sender.getUniqueId())) return null;
        String body = rawBody.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        if (body.isBlank() || body.length() > 2000) return null;
        String messageId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO mail_messages(id,sender_uuid,sender_name,recipient_uuid,recipient_name,body,created_at,read_at) VALUES(?,?,?,?,?,?,?,NULL)")) {
                statement.setString(1, messageId);
                statement.setString(2, sender.getUniqueId().toString());
                statement.setString(3, sender.getName());
                statement.setString(4, recipient.uuid().toString());
                statement.setString(5, recipient.name());
                statement.setString(6, body);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            database.logAudit("EMAIL_SENT", sender.getUniqueId(), recipient.uuid().toString(), "message=" + messageId);
            return null;
        });
        emailNotifier.accept(recipient.uuid(), sender.getName());
        return messageId;
    }

    public List<MailMessage> getEmails(UUID recipient) {
        List<MailMessage> messages = new ArrayList<>();
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT * FROM mail_messages WHERE recipient_uuid=? ORDER BY created_at DESC")) {
            statement.setString(1, recipient.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) messages.add(readMailMessage(result));
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to load mailbox email", e);
        }
        return messages;
    }

    public int countUnreadEmails(UUID recipient) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM mail_messages WHERE recipient_uuid=? AND read_at IS NULL")) {
            statement.setString(1, recipient.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to count unread email", e);
        }
    }

    public MailMessage getEmail(UUID recipient, String messageId) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT * FROM mail_messages WHERE id=? AND recipient_uuid=?")) {
            statement.setString(1, messageId);
            statement.setString(2, recipient.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readMailMessage(result) : null;
            }
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Unable to load selected email", e);
        }
    }

    public boolean markEmailRead(UUID recipient, String messageId) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "UPDATE mail_messages SET read_at=? WHERE id=? AND recipient_uuid=? AND read_at IS NULL")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, messageId);
            statement.setString(3, recipient.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to mark email read", e);
        }
    }

    private MailMessage readMailMessage(ResultSet result) throws SQLException {
        long readAt = result.getLong("read_at");
        Long readTimestamp = result.wasNull() ? null : readAt;
        return new MailMessage(result.getString("id"), UUID.fromString(result.getString("sender_uuid")),
                result.getString("sender_name"), UUID.fromString(result.getString("recipient_uuid")),
                result.getString("recipient_name"), result.getString("body"), result.getLong("created_at"), readTimestamp);
    }

    public String createGift(Player sender, UUID recipient, String recipientName, List<ItemStack> items) {
        if (!sender.hasPermission("bartermarket.gift") || sender.getUniqueId().equals(recipient)
                || items.isEmpty() || items.size() > 25 || !validItems(items)
                || !containsItems(sender.getInventory(), items)) {
            return null;
        }
        String transactionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        List<ItemStack> giftItems = copyItems(items);
        String payload = encodeItems(giftItems);
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO transactions(id,listing_id,buyer_uuid,seller_uuid,state,payload,created_at,updated_at) VALUES(?,NULL,?,?,'PREPARED',?,?,?)")) {
                statement.setString(1, transactionId);
                statement.setString(2, recipient.toString());
                statement.setString(3, sender.getUniqueId().toString());
                statement.setString(4, payload);
                statement.setLong(5, now);
                statement.setLong(6, now);
                statement.executeUpdate();
            }
            return null;
        });
        if (!removeItems(sender.getInventory(), giftItems)) {
            database.transaction(connection -> {
                setTransactionState(connection, transactionId, "FAILED");
                return null;
            });
            return null;
        }
        try {
            database.transaction(connection -> {
                insertDelivery(connection, recipient, transactionId, giftItems, sender.getName());
                setTransactionState(connection, transactionId, "DELIVERY_PENDING");
                database.logAudit("GIFT_SENT", sender.getUniqueId(), recipient.toString(),
                        "transaction=" + transactionId + ", recipient=" + recipientName);
                return null;
            });
        } catch (RuntimeException e) {
            try {
                database.transaction(connection -> {
                    setTransactionState(connection, transactionId, "RECOVERY_REQUIRED");
                    return null;
                });
            } catch (RuntimeException recoveryError) {
                e.addSuppressed(recoveryError);
            }
            throw e;
        }
        loadDeliveries();
        mailboxNotifier.accept(recipient, sender.getName());
        return transactionId;
    }

    public String createListing(Player seller, List<ItemStack> offers, List<ItemStack> requests, long expiryMillis) {
        if (!seller.hasPermission("bartermarket.sell") || expiryMillis <= 0
                || getListingsForSeller(seller.getUniqueId()).stream().filter(listing -> "ACTIVE".equals(listing.getStatus())).count() >= maxListingsPerPlayer
            || offers.isEmpty() || requests.isEmpty() || !validItems(offers) || !validItems(requests)
                || !containsItems(seller.getInventory(), offers)) {
            return null;
        }

        String listingId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        List<ItemStack> offerCopy = copyItems(offers);
        List<ItemStack> requestCopy = copyItems(requests);
        String payload = encodeItems(offerCopy) + "\n" + encodeItems(requestCopy);
        database.transaction(connection -> {
            try (PreparedStatement listingInsert = connection.prepareStatement(
                    "INSERT INTO listings(id,seller_uuid,seller_name,offer_payload,request_payload,created_at,expires_at,status,reservation_state) VALUES(?,?,?,?,?,?,?,'RESERVING','LISTING_RESERVED')");
                 PreparedStatement transactionInsert = connection.prepareStatement(
                         "INSERT INTO transactions(id,listing_id,buyer_uuid,seller_uuid,state,payload,created_at,updated_at) VALUES(?,?,NULL,?,'LISTING_RESERVING',?,?,?)")) {
                listingInsert.setString(1, listingId);
                listingInsert.setString(2, seller.getUniqueId().toString());
                listingInsert.setString(3, seller.getName());
                listingInsert.setString(4, encodeItems(offerCopy));
                listingInsert.setString(5, encodeItems(requestCopy));
                listingInsert.setLong(6, now);
                listingInsert.setLong(7, now + expiryMillis);
                listingInsert.executeUpdate();
                transactionInsert.setString(1, transactionId);
                transactionInsert.setString(2, listingId);
                transactionInsert.setString(3, seller.getUniqueId().toString());
                transactionInsert.setString(4, payload);
                transactionInsert.setLong(5, now);
                transactionInsert.setLong(6, now);
                transactionInsert.executeUpdate();
            }
            return null;
        });

        if (!removeItems(seller.getInventory(), offerCopy)) {
            quarantineListing(listingId, transactionId, "Offer removal failed after reservation journal");
            return null;
        }
        try {
            database.transaction(connection -> {
                try (PreparedStatement listingUpdate = connection.prepareStatement(
                        "UPDATE listings SET status='ACTIVE', reservation_state='LISTING_RESERVED' WHERE id=? AND status='RESERVING'");
                     PreparedStatement transactionUpdate = connection.prepareStatement(
                             "UPDATE transactions SET state='COMPLETED', updated_at=? WHERE id=? AND state='LISTING_RESERVING'")) {
                    listingUpdate.setString(1, listingId);
                    if (listingUpdate.executeUpdate() != 1) {
                        throw new SQLException("Listing reservation state changed unexpectedly");
                    }
                    transactionUpdate.setLong(1, System.currentTimeMillis());
                    transactionUpdate.setString(2, transactionId);
                    transactionUpdate.executeUpdate();
                }
                database.logAudit("LISTING_CREATED", seller.getUniqueId(), listingId, "transaction=" + transactionId);
                return null;
            });
        } catch (RuntimeException e) {
            try {
                quarantineListing(listingId, transactionId,
                        "Offer inventory was debited but listing activation failed; manual reconciliation required");
            } catch (RuntimeException quarantineError) {
                e.addSuppressed(quarantineError);
            }
            throw e;
        }
        MarketListing listing = new MarketListing(listingId, seller.getUniqueId(), seller.getName(), offerCopy,
                requestCopy, now, now + expiryMillis);
        listings.put(listingId, listing);
        return listingId;
    }

    public boolean purchaseListing(Player buyer, String listingId) {
        MarketListing listing = listings.get(listingId);
        if (!buyer.hasPermission("bartermarket.use") || listing == null || !"ACTIVE".equals(listing.getStatus()) || listing.getExpiresAt() <= System.currentTimeMillis()
                || listing.getSellerUuid().equals(buyer.getUniqueId())
                || !containsItems(buyer.getInventory(), listing.getRequested())) {
            return false;
        }
        String transactionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String txPayload = encodeItems(listing.getOffer()) + "\n" + encodeItems(listing.getRequested());
        boolean locked = database.transaction(connection -> {
            try (PreparedStatement lock = connection.prepareStatement(
                    "UPDATE listings SET status='LOCKED' WHERE id=? AND status='ACTIVE' AND expires_at>?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO transactions(id,listing_id,buyer_uuid,seller_uuid,state,payload,created_at,updated_at) VALUES(?,?,?,?,'PREPARED',?,?,?)")) {
                lock.setString(1, listingId);
                lock.setLong(2, now);
                if (lock.executeUpdate() != 1) {
                    return false;
                }
                insert.setString(1, transactionId);
                insert.setString(2, listingId);
                insert.setString(3, buyer.getUniqueId().toString());
                insert.setString(4, listing.getSellerUuid().toString());
                insert.setString(5, txPayload);
                insert.setLong(6, now);
                insert.setLong(7, now);
                insert.executeUpdate();
                return true;
            }
        });
        if (!locked) {
            return false;
        }
        listing.setStatus("LOCKED");
        if (!removeItems(buyer.getInventory(), listing.getRequested())) {
            database.transaction(connection -> {
                setListingStatus(connection, listingId, "LOCKED", "ACTIVE");
                setTransactionState(connection, transactionId, "FAILED");
                return null;
            });
            listing.setStatus("ACTIVE");
            return false;
        }
        try {
            database.transaction(connection -> {
                setTransactionState(connection, transactionId, "PAYMENT_DEBITED");
                insertDelivery(connection, UUID.fromString(listing.getSellerUuid().toString()), transactionId,
                        listing.getRequested());
                insertDelivery(connection, buyer.getUniqueId(), transactionId, listing.getOffer());
                setListingStatus(connection, listingId, "LOCKED", "SOLD");
                setTransactionState(connection, transactionId, "DELIVERY_PENDING");
                database.logAudit("PURCHASE_COMMITTED", buyer.getUniqueId(), listingId,
                        "transaction=" + transactionId + ", seller=" + listing.getSellerUuid());
                return null;
            });
        } catch (RuntimeException e) {
            buyer.sendMessage("Purchase outcome is being quarantined for administrator review. Do not retry payment.");
            return false;
        }
        listing.setStatus("SOLD");
        loadDeliveries();
        mailboxNotifier.accept(buyer.getUniqueId(), "");
        mailboxNotifier.accept(listing.getSellerUuid(), "");
        return true;
    }

    public boolean cancelListing(UUID seller, String listingId) {
        MarketListing listing = listings.get(listingId);
        if (listing == null || !listing.getSellerUuid().equals(seller)) {
            return false;
        }
        String transactionId = UUID.randomUUID().toString();
        boolean canceled = database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE listings SET status='CANCELLED', reservation_state='DELIVERY_PENDING' WHERE id=? AND seller_uuid=? AND status='ACTIVE'");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO transactions(id,listing_id,buyer_uuid,seller_uuid,state,payload,created_at,updated_at) VALUES(?,?,NULL,?,'RETURN_ASSIGNED',?,?,?)")) {
                update.setString(1, listingId);
                update.setString(2, seller.toString());
                if (update.executeUpdate() != 1) {
                    return false;
                }
                long now = System.currentTimeMillis();
                insert.setString(1, transactionId);
                insert.setString(2, listingId);
                insert.setString(3, seller.toString());
                insert.setString(4, encodeItems(listing.getOffer()));
                insert.setLong(5, now);
                insert.setLong(6, now);
                insert.executeUpdate();
                insertDelivery(connection, seller, transactionId, listing.getOffer());
                database.logAudit("LISTING_CANCELLED", seller, listingId, "transaction=" + transactionId);
                return true;
            }
        });
        if (canceled) {
            listing.setStatus("CANCELLED");
            loadDeliveries();
            mailboxNotifier.accept(seller, "");
        }
        return canceled;
    }

    public boolean undoLatestListing(UUID seller) {
        return getListingsForSeller(seller).stream().filter(listing -> "ACTIVE".equals(listing.getStatus()))
                .max(Comparator.comparingLong(MarketListing::getCreatedAt))
                .map(listing -> cancelListing(seller, listing.getId())).orElse(false);
    }

    public List<DeliveryRecord> getPendingDeliveries(UUID recipient) {
        return deliveries.values().stream().filter(delivery -> delivery.getRecipientUuid().equals(recipient)
                && "PENDING".equals(delivery.getStatus())).toList();
    }

    public int pendingDeliveryCount() {
        return (int) deliveries.values().stream().filter(delivery -> "PENDING".equals(delivery.getStatus())).count();
    }

    public boolean claimDelivery(Player recipient, String deliveryId) {
        DeliveryRecord delivery = deliveries.get(deliveryId);
        if (delivery == null || !delivery.getRecipientUuid().equals(recipient.getUniqueId())
                || !"PENDING".equals(delivery.getStatus()) || !hasStorageSpace(recipient.getInventory(), delivery.getItems())) {
            return false;
        }
        boolean reserved = database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE deliveries SET status='CLAIMING' WHERE id=? AND recipient_uuid=? AND status='PENDING'")) {
                update.setString(1, deliveryId);
                update.setString(2, recipient.getUniqueId().toString());
                return update.executeUpdate() == 1;
            }
        });
        if (!reserved) {
            return false;
        }
        delivery.setStatus("CLAIMING");
        Map<Integer, ItemStack> overflow = recipient.getInventory().addItem(copyItems(delivery.getItems()).toArray(ItemStack[]::new));
        if (!overflow.isEmpty()) {
            setDeliveryStatus(deliveryId, "QUARANTINED");
            delivery.setStatus("QUARANTINED");
            database.logAudit("DELIVERY_QUARANTINED", recipient.getUniqueId(), deliveryId, "unexpected inventory overflow");
            return false;
        }
        setDeliveryStatus(deliveryId, "CLAIMED");
        delivery.setStatus("CLAIMED");
        completeTransactionIfDelivered(delivery.getTransactionId());
        database.logAudit("DELIVERY_CLAIMED", recipient.getUniqueId(), deliveryId, "transaction=" + delivery.getTransactionId());
        return true;
    }

    public boolean cancelPendingDelivery(UUID recipient, String deliveryId) {
        DeliveryRecord delivery = deliveries.get(deliveryId);
        return delivery != null && delivery.getRecipientUuid().equals(recipient) && "PENDING".equals(delivery.getStatus());
    }

    public void expireListings() {
        long now = System.currentTimeMillis();
        List<MarketListing> expired = listings.values().stream()
                .filter(listing -> "ACTIVE".equals(listing.getStatus()) && listing.getExpiresAt() <= now).toList();
        for (MarketListing listing : expired) {
            cancelListing(listing.getSellerUuid(), listing.getId());
        }
    }

    private void quarantineListing(String listingId, String transactionId, String reason) {
        database.transaction(connection -> {
                try (PreparedStatement listing = connection.prepareStatement("UPDATE listings SET status='QUARANTINED' WHERE id=?");
                 PreparedStatement transaction = connection.prepareStatement(
                     "UPDATE transactions SET state='RECOVERY_REQUIRED', updated_at=? WHERE id=?")) {
                listing.setString(1, listingId);
                listing.executeUpdate();
                transaction.setLong(1, System.currentTimeMillis());
                transaction.setString(2, transactionId);
                transaction.executeUpdate();
            }
            database.logAudit("LISTING_QUARANTINED", null, listingId, reason);
            return null;
        });
    }

    private void insertDelivery(Connection connection, UUID recipient, String transactionId, List<ItemStack> items)
            throws SQLException {
        insertDelivery(connection, recipient, transactionId, items, "");
        }

        private void insertDelivery(Connection connection, UUID recipient, String transactionId, List<ItemStack> items,
                    String senderName) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO deliveries(id,recipient_uuid,transaction_id,payload,status,created_at,claimed_at,sender_name) VALUES(?,?,?,?,'PENDING',?,NULL,?)")) {
            statement.setString(1, id);
            statement.setString(2, recipient.toString());
            statement.setString(3, transactionId);
            statement.setString(4, encodeItems(items));
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, senderName);
            statement.executeUpdate();
        }
    }

    private void setListingStatus(Connection connection, String id, String from, String to) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE listings SET status=? WHERE id=? AND status=?")) {
            statement.setString(1, to);
            statement.setString(2, id);
            statement.setString(3, from);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Listing state transition rejected: " + from + " -> " + to);
            }
        }
    }

    private void setTransactionState(Connection connection, String id, String state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET state=?, updated_at=? WHERE id=?")) {
            statement.setString(1, state);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Missing transaction journal: " + id);
            }
        }
    }

    private void setDeliveryStatus(String id, String status) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE deliveries SET status=?, claimed_at=CASE WHEN ?='CLAIMED' THEN ? ELSE claimed_at END WHERE id=? AND status='CLAIMING'")) {
                statement.setString(1, status);
                statement.setString(2, status);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, id);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Delivery state transition rejected: " + id);
                }
            }
            return null;
        });
    }

    private void completeTransactionIfDelivered(String transactionId) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state='COMPLETED', updated_at=? WHERE id=? AND NOT EXISTS "
                            + "(SELECT 1 FROM deliveries WHERE transaction_id=? AND status<>'CLAIMED')")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
                statement.setString(3, transactionId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private boolean containsItems(Inventory inventory, List<ItemStack> items) {
        List<ItemStack> remaining = new ArrayList<>(copyItems(items));
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            int available = stack.getAmount();
            for (int index = 0; index < remaining.size(); index++) {
                ItemStack requirement = remaining.get(index);
                if (!stack.isSimilar(requirement)) {
                    continue;
                }
                int taken = Math.min(available, requirement.getAmount());
                requirement.setAmount(requirement.getAmount() - taken);
                available -= taken;
                if (requirement.getAmount() == 0) {
                    remaining.remove(index);
                    index--;
                }
                if (available == 0) break;
            }
        }
        return remaining.isEmpty();
    }

    private boolean removeItems(Inventory inventory, List<ItemStack> items) {
        if (!containsItems(inventory, items)) {
            return false;
        }
        for (ItemStack requirement : items) {
            int remaining = requirement.getAmount();
            for (int slot = 0; slot < inventory.getStorageContents().length && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (current == null || !current.isSimilar(requirement)) {
                    continue;
                }
                int amount = Math.min(current.getAmount(), remaining);
                current.setAmount(current.getAmount() - amount);
                remaining -= amount;
                inventory.setItem(slot, current.getAmount() <= 0 ? null : current);
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean hasStorageSpace(Inventory inventory, List<ItemStack> items) {
        List<ItemStack> simulated = new ArrayList<>();
        for (ItemStack item : inventory.getStorageContents()) {
            simulated.add(item == null ? null : item.clone());
        }
        for (ItemStack offered : items) {
            int remaining = offered.getAmount();
            for (ItemStack existing : simulated) {
                if (existing != null && existing.isSimilar(offered) && existing.getAmount() < existing.getMaxStackSize()) {
                    int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
                    existing.setAmount(existing.getAmount() + moved);
                    remaining -= moved;
                    if (remaining == 0) {
                        break;
                    }
                }
            }
            for (int index = 0; index < simulated.size() && remaining > 0; index++) {
                if (simulated.get(index) == null || simulated.get(index).getType().isAir()) {
                    int moved = Math.min(remaining, offered.getMaxStackSize());
                    ItemStack piece = offered.clone();
                    piece.setAmount(moved);
                    simulated.set(index, piece);
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean validItems(List<ItemStack> items) {
        return items.stream().allMatch(item -> item != null && !item.getType().isAir() && item.getAmount() > 0
                && item.getAmount() <= item.getMaxStackSize());
    }

    private List<ItemStack> copyItems(List<ItemStack> items) {
        return items.stream().map(ItemStack::clone).toList();
    }

    public static String encodeItems(List<ItemStack> items) {
        return items.stream().map(ItemStackCodec::serialize).reduce((left, right) -> left + "::" + right).orElse("");
    }

    public static List<ItemStack> decodeItems(String payload) {
        List<ItemStack> items = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return items;
        }
        for (String encoded : payload.split("::")) {
            ItemStack item = ItemStackCodec.deserialize(encoded);
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                throw new IllegalArgumentException("Invalid item payload");
            }
            items.add(item);
        }
        return items;
    }
}
