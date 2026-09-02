package com.razorpay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite connection and schema bootstrap for the entire AgentPay
 * subsystem.  One file, one responsibility: initialise tables on first run.
 *
 * Tables created here:
 *   agent_policy     – per-agent allow-list and daily budget state
 *   audit_log        – immutable append-only ledger of every gateway decision
 *   usage_ledger     – running accumulated paise per (agent, resource) pair
 */
public class AgentDatabase {

    private static final String DB_URL = "jdbc:sqlite:agentpay.db";

    private AgentDatabase() {}

    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        // Enable WAL for better concurrent read/write throughput with SQLite.
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    public static void initialize() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {

            st.execute(
                "CREATE TABLE IF NOT EXISTS agent_policy (" +
                "  agent_id       TEXT    PRIMARY KEY," +
                "  allowed        INTEGER NOT NULL DEFAULT 1," +
                "  daily_limit_paise BIGINT NOT NULL DEFAULT 50000," +
                "  daily_spent_paise BIGINT NOT NULL DEFAULT 0," +
                "  last_reset_date   TEXT   NOT NULL DEFAULT (date('now'))" +
                ")"
            );

            st.execute(
                "CREATE TABLE IF NOT EXISTS audit_log (" +
                "  id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  agent_id       TEXT    NOT NULL," +
                "  resource_id    TEXT," +
                "  amount_paise   BIGINT  NOT NULL DEFAULT 0," +
                "  decision       TEXT    NOT NULL," +
                "  reason         TEXT," +
                "  razorpay_order_id  TEXT," +
                "  razorpay_payment_id TEXT," +
                "  created_at     TEXT    NOT NULL DEFAULT (datetime('now'))" +
                ")"
            );

            st.execute(
                "CREATE TABLE IF NOT EXISTS usage_ledger (" +
                "  agent_id           TEXT   NOT NULL," +
                "  resource_id        TEXT   NOT NULL," +
                "  accumulated_paise  BIGINT NOT NULL DEFAULT 0," +
                "  threshold_paise    BIGINT NOT NULL DEFAULT 100," +
                "  tick_paise         BIGINT NOT NULL DEFAULT 10," +
                "  pending_order_id   TEXT," +
                "  PRIMARY KEY (agent_id, resource_id)" +
                ")"
            );

            st.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_payment " +
                "ON audit_log(razorpay_payment_id) " +
                "WHERE razorpay_payment_id IS NOT NULL"
            );

            System.out.println("[AgentDatabase] Schema ready.");

        } catch (SQLException e) {
            throw new RuntimeException("AgentDatabase schema initialisation failed: " + e.getMessage(), e);
        }
    }

    public static void insertDefaultPolicy(Connection conn, String agentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO agent_policy(agent_id, allowed, daily_limit_paise, " +
            "daily_spent_paise, last_reset_date) VALUES (?, 1, 50000, 0, date('now'))"
        )) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        }
    }
}
