package com.razorpay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * SQLite connection factory and schema bootstrap.
 *
 * Tables:
 *   agent_policy       — per-agent allow/deny, daily spend cap, and current
 *                        reservation (amount held pending payment capture)
 *   payment_challenge  — server-side record of every 402 order issued,
 *                        binding order_id → agent + resource + amount.
 *                        Verification refuses to unlock a resource unless a
 *                        matching PENDING challenge exists.
 *   audit_log          — append-only decision log
 *   usage_ledger       — running paise accumulator for the metering flow
 *
 * WAL mode on every connection so concurrent reads don't block writes.
 */
public class AgentDatabase {

    private static String DB_URL = "jdbc:sqlite:agentpay.db";

    private AgentDatabase() {}

    // Called once from App.run() so the configured path is used everywhere.
    public static void setDbPath(String path) {
        DB_URL = "jdbc:sqlite:" + path;
    }

    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    public static void initialize() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {

            // reserved_paise: amount held while a 402 order is outstanding.
            // available = daily_limit - daily_spent - reserved_paise
            st.execute(
                "CREATE TABLE IF NOT EXISTS agent_policy (" +
                "  agent_id           TEXT   PRIMARY KEY," +
                "  allowed            INTEGER NOT NULL DEFAULT 1," +
                "  daily_limit_paise  BIGINT  NOT NULL DEFAULT 50000," +
                "  daily_spent_paise  BIGINT  NOT NULL DEFAULT 0," +
                "  reserved_paise     BIGINT  NOT NULL DEFAULT 0," +
                "  last_reset_date    TEXT    NOT NULL DEFAULT (date('now'))" +
                ")"
            );

            // Authoritative mapping: every 402 order issued is recorded here.
            // verifyAndUnlock() refuses to proceed unless a PENDING row exists
            // with matching agent_id, resource_id, and amount_paise.
            st.execute(
                "CREATE TABLE IF NOT EXISTS payment_challenge (" +
                "  order_id     TEXT   PRIMARY KEY," +
                "  agent_id     TEXT   NOT NULL," +
                "  resource_id  TEXT   NOT NULL," +
                "  amount_paise BIGINT NOT NULL," +
                "  status       TEXT   NOT NULL DEFAULT 'PENDING'," +
                "  created_at   TEXT   NOT NULL DEFAULT (datetime('now'))" +
                ")"
            );

            st.execute(
                "CREATE TABLE IF NOT EXISTS audit_log (" +
                "  id                  INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  agent_id            TEXT    NOT NULL," +
                "  resource_id         TEXT," +
                "  amount_paise        BIGINT  NOT NULL DEFAULT 0," +
                "  decision            TEXT    NOT NULL," +
                "  reason              TEXT," +
                "  razorpay_order_id   TEXT," +
                "  razorpay_payment_id TEXT," +
                "  created_at          TEXT    NOT NULL DEFAULT (datetime('now'))" +
                ")"
            );

            st.execute(
                "CREATE TABLE IF NOT EXISTS usage_ledger (" +
                "  agent_id           TEXT   NOT NULL," +
                "  resource_id        TEXT   NOT NULL," +
                "  accumulated_paise  BIGINT NOT NULL DEFAULT 0," +
                "  threshold_paise    BIGINT NOT NULL DEFAULT 500," +
                "  tick_paise         BIGINT NOT NULL DEFAULT 50," +
                "  pending_order_id   TEXT," +
                "  PRIMARY KEY (agent_id, resource_id)" +
                ")"
            );

            // Partial unique index: a payment_id can only appear once as VERIFIED.
            // NULL payment_ids (policy-check rows) are excluded.
            st.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_payment " +
                "ON audit_log(razorpay_payment_id) " +
                "WHERE razorpay_payment_id IS NOT NULL"
            );

            // Add reserved_paise column if upgrading from an older schema.
            try { st.execute("ALTER TABLE agent_policy ADD COLUMN reserved_paise BIGINT NOT NULL DEFAULT 0"); }
            catch (SQLException ignored) {} // column already exists

            System.out.println("[AgentDatabase] schema ready");

        } catch (SQLException e) {
            throw new RuntimeException("DB init failed: " + e.getMessage(), e);
        }
    }

    public static void insertDefaultPolicy(Connection conn, String agentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO agent_policy" +
            "(agent_id,allowed,daily_limit_paise,daily_spent_paise,reserved_paise,last_reset_date)" +
            " VALUES(?,1,50000,0,0,date('now'))"
        )) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        }
    }
}
