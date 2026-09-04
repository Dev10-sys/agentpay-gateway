package com.razorpay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * AgentDatabase owns the SQLite connection factory and schema bootstrap.
 *
 * Schema summary:
 *
 *   agent_policy
 *     Per-agent allow/block flag and daily spending cap.  Rows are
 *     auto-inserted with permissive defaults on first access, so there is
 *     no out-of-band provisioning step for new agents.
 *
 *   audit_log
 *     Append-only ledger of every gateway decision (APPROVED, DENIED,
 *     VERIFIED, DUPLICATE, ERROR).  The unique index on razorpay_payment_id
 *     is the database-level replay guard: inserting the same payment_id
 *     twice raises a constraint violation before any resource is released.
 *
 *   usage_ledger
 *     Running paise accumulator for the micro-usage metering feature.
 *     One row per (agent_id, resource_id) pair.  pending_order_id holds
 *     the Razorpay order that the agent must settle before ticking resumes.
 *
 * WAL mode is enabled on every connection so concurrent reads from the
 * simulator and server do not block each other.
 */
public class AgentDatabase {

    private static final String DB_URL = "jdbc:sqlite:agentpay.db";

    // Utility class — no instances.
    private AgentDatabase() {}

    /**
     * Returns a new JDBC connection to the local SQLite file.
     * WAL journal mode and a 5-second busy timeout are set on every connection
     * to tolerate brief write contention under the concurrent test load.
     */
    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    /**
     * Creates all tables and indexes if they do not already exist.
     * Safe to call on every startup; all DDL statements use IF NOT EXISTS.
     * Throws RuntimeException (and aborts startup) on any DDL failure.
     */
    public static void initialize() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {

            st.execute(
                "CREATE TABLE IF NOT EXISTS agent_policy (" +
                "  agent_id           TEXT   PRIMARY KEY," +
                "  allowed            INTEGER NOT NULL DEFAULT 1," +
                "  daily_limit_paise  BIGINT  NOT NULL DEFAULT 50000," +
                "  daily_spent_paise  BIGINT  NOT NULL DEFAULT 0," +
                "  last_reset_date    TEXT    NOT NULL DEFAULT (date('now'))" +
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
                "  threshold_paise    BIGINT NOT NULL DEFAULT 100," +
                "  tick_paise         BIGINT NOT NULL DEFAULT 10," +
                "  pending_order_id   TEXT," +
                "  PRIMARY KEY (agent_id, resource_id)" +
                ")"
            );

            // Unique index on razorpay_payment_id is the database-level
            // duplicate-payment guard.  Null values are excluded so
            // non-payment audit rows (e.g. policy checks) do not conflict.
            st.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_payment " +
                "ON audit_log(razorpay_payment_id) " +
                "WHERE razorpay_payment_id IS NOT NULL"
            );

            System.out.println("[AgentDatabase] Schema ready.");

        } catch (SQLException e) {
            throw new RuntimeException("Schema initialisation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Inserts a default policy row for agentId if one does not exist yet.
     * Must be called inside an open transaction so the caller controls
     * the commit/rollback boundary.
     */
    public static void insertDefaultPolicy(Connection conn, String agentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO agent_policy" +
            "(agent_id, allowed, daily_limit_paise, daily_spent_paise, last_reset_date)" +
            " VALUES (?, 1, 50000, 0, date('now'))"
        )) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        }
    }
}
