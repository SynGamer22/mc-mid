package com.bartermarket;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

public class MarketDatabase {
    private final JavaPlugin plugin;
    private final File databaseFile;
    private Connection connection;

    public MarketDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "database.db");
        initialize();
    }

    private void initialize() {
        plugin.getDataFolder().mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            initializeSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Unable to initialize SQLite database", e);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS listings (" +
                    "id TEXT PRIMARY KEY, " +
                    "seller_uuid TEXT NOT NULL, " +
                    "seller_name TEXT NOT NULL, " +
                    "offer_payload TEXT NOT NULL, " +
                    "request_payload TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "expires_at INTEGER NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "reservation_state TEXT NOT NULL DEFAULT 'NONE'" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS deliveries (" +
                    "id TEXT PRIMARY KEY, " +
                    "recipient_uuid TEXT NOT NULL, " +
                    "transaction_id TEXT NOT NULL, " +
                    "payload TEXT NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'PENDING', " +
                    "created_at INTEGER NOT NULL, " +
                    "claimed_at INTEGER DEFAULT NULL, " +
                    "sender_name TEXT NOT NULL DEFAULT ''" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id TEXT PRIMARY KEY, " +
                    "listing_id TEXT, " +
                    "buyer_uuid TEXT, " +
                    "seller_uuid TEXT, " +
                    "state TEXT NOT NULL, " +
                    "payload TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS audit_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "event TEXT NOT NULL, " +
                    "actor_uuid TEXT, " +
                    "target TEXT, " +
                    "details TEXT, " +
                    "created_at INTEGER NOT NULL" +
                    ")");

                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_directory (" +
                        "player_uuid TEXT PRIMARY KEY, " +
                        "player_name TEXT NOT NULL COLLATE NOCASE, " +
                        "last_seen INTEGER NOT NULL" +
                        ")");

                        statement.executeUpdate("CREATE TABLE IF NOT EXISTS mail_messages (" +
                            "id TEXT PRIMARY KEY, " +
                            "sender_uuid TEXT NOT NULL, " +
                            "sender_name TEXT NOT NULL, " +
                            "recipient_uuid TEXT NOT NULL, " +
                            "recipient_name TEXT NOT NULL, " +
                            "body TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, " +
                            "read_at INTEGER DEFAULT NULL" +
                            ")");

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_listings_seller ON listings(seller_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_listings_status ON listings(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deliveries_recipient ON deliveries(recipient_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_listing ON transactions(listing_id)");
        }
        ensureColumn("deliveries", "sender_name", "TEXT NOT NULL DEFAULT ''");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name ON player_directory(player_name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mail_recipient ON mail_messages(recipient_uuid, created_at)");
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public void recordPlayer(UUID playerUuid, String playerName) {
        String sql = "INSERT INTO player_directory(player_uuid,player_name,last_seen) VALUES(?,?,?) "
                + "ON CONFLICT(player_uuid) DO UPDATE SET player_name=excluded.player_name,last_seen=excluded.last_seen";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to record known player", e);
        }
    }

    public synchronized <T> T transaction(SqlWork<T> work) {
        try {
            connection.setAutoCommit(false);
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw new IllegalStateException("SQLite transaction failed", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                plugin.getLogger().severe("Unable to restore SQLite autocommit: " + e.getMessage());
            }
        }
    }

    public synchronized void logAudit(String event, UUID actor, String target, String details) {
        String sql = "INSERT INTO audit_log(event, actor_uuid, target, details, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event);
            statement.setString(2, actor == null ? null : actor.toString());
            statement.setString(3, target);
            statement.setString(4, details);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Audit log write failed: " + e.getMessage());
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Unable to close database: " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }
}
