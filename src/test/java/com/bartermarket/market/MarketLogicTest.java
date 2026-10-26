package com.bartermarket.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketLogicTest {
    @TempDir
    Path tempDir;
    private Path databasePath;

    @BeforeEach
    void createDatabase() throws Exception {
        databasePath = tempDir.resolve("market.db");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE listings(id TEXT PRIMARY KEY, status TEXT NOT NULL)");
            statement.execute("CREATE TABLE deliveries(id TEXT PRIMARY KEY, status TEXT NOT NULL)");
            statement.execute("CREATE TABLE transactions(id TEXT PRIMARY KEY, state TEXT NOT NULL, updated_at INTEGER)");
            statement.execute("INSERT INTO listings VALUES('listing-1','ACTIVE')");
            statement.execute("INSERT INTO deliveries VALUES('delivery-1','PENDING')");
            statement.execute("INSERT INTO transactions VALUES('tx-1','PREPARED',0)");
        }
    }

    @Test
    void concurrentBuyersOnlyOneAcquiresListing() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> acquireListing(start)),
                    executor.submit(() -> acquireListing(start)));
            start.countDown();
            assertEquals(1, attempts.stream().filter(future -> get(future)).count());
        }
        assertEquals("LOCKED", value("SELECT status FROM listings WHERE id='listing-1'"));
    }

    @Test
    void concurrentClaimsOnlyOneAcquiresDelivery() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> acquireDelivery(start)),
                    executor.submit(() -> acquireDelivery(start)));
            start.countDown();
            assertEquals(1, attempts.stream().filter(future -> get(future)).count());
        }
        assertEquals("CLAIMING", value("SELECT status FROM deliveries WHERE id='delivery-1'"));
    }

    @Test
    void interruptedOperationsAreQuarantinedNotReplayed() throws Exception {
        try (Connection connection = open(); PreparedStatement listing = connection.prepareStatement(
                "UPDATE listings SET status='QUARANTINED' WHERE status IN ('RESERVING','LOCKED','RETURN_PENDING')");
             PreparedStatement delivery = connection.prepareStatement(
                     "UPDATE deliveries SET status='QUARANTINED' WHERE status='CLAIMING'");
             PreparedStatement transaction = connection.prepareStatement(
                     "UPDATE transactions SET state='RECOVERY_REQUIRED', updated_at=? WHERE state IN ('LISTING_RESERVING','PREPARED','PAYMENT_DEBITED')")) {
            try (Statement setup = connection.createStatement()) {
                setup.execute("UPDATE listings SET status='LOCKED'");
                setup.execute("UPDATE deliveries SET status='CLAIMING'");
            }
            listing.executeUpdate();
            delivery.executeUpdate();
            transaction.setLong(1, System.currentTimeMillis());
            transaction.executeUpdate();
        }
        assertEquals("QUARANTINED", value("SELECT status FROM listings WHERE id='listing-1'"));
        assertEquals("QUARANTINED", value("SELECT status FROM deliveries WHERE id='delivery-1'"));
        assertEquals("RECOVERY_REQUIRED", value("SELECT state FROM transactions WHERE id='tx-1'"));
    }

    @Test
    void failedDatabaseTransactionRollsBackListingLock() throws Exception {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE listings SET status='LOCKED' WHERE id='listing-1' AND status='ACTIVE'");
                statement.executeUpdate("INSERT INTO transactions VALUES('tx-1','PREPARED',0)");
            } catch (Exception expectedConstraintFailure) {
                connection.rollback();
            }
        }
        assertEquals("ACTIVE", value("SELECT status FROM listings WHERE id='listing-1'"));
    }

    private boolean acquireListing(CountDownLatch start) throws Exception {
        start.await();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE listings SET status='LOCKED' WHERE id='listing-1' AND status='ACTIVE'")) {
            return statement.executeUpdate() == 1;
        }
    }

    private boolean acquireDelivery(CountDownLatch start) throws Exception {
        start.await();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE deliveries SET status='CLAIMING' WHERE id='delivery-1' AND status='PENDING'")) {
            return statement.executeUpdate() == 1;
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private String value(String sql) throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("Concurrent SQLite operation failed", e);
        }
    }
}
