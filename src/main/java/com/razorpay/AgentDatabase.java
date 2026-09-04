package com.razorpay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/*
 * SQLite connection factory and schema bootstrap.
 *
 * Tables:
 *   agent_policy       — per-agent allow/deny, daily spend cap, and current
 *                        reservation (amount held pending payment capture)
 *   payment_challenge  — authoritative binding: every 402 order maps to exactly
 *                        one (agent, resource, amount). Status: PENDING → CONSUMED | EXPIRED.
 *   audit_log          — append-only decision log
 *   usage_ledger       — running paise accumulator for the metering flow
 *   payment_event      — webhook event deduplication (UNIQUE on event_type + payment_id)
 *
 * WAL mode on every connection so concurrent reads don't block writes.
 */
public class AgentDatabase {

    // Challenge reservations expire after 15 minutes of inactivity.
    static final int CHALLENGE_EXPIRY_MINUTES = 15;

    private static String DB_URL = "jdbc:sqlite:agentpay.db";

    private AgentDatabase() {}

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

            // Webhook event deduplication — prevents duplicate payment.captured from double-crediting.
            st.execute(
                "CREATE TABLE IF NOT EXISTS payment_event (" +
                "  id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  event_type  TEXT NOT NULL," +
                "  payment_id  TEXT NOT NULL," +
                "  order_id    TEXT," +
                "  received_at TEXT NOT NULL DEFAULT (datetime('now'))" +
                ")"
            );

            st.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_event " +
                "ON payment_event(event_type, payment_id)"
            );

            // Partial unique index: only VERIFIED rows participate in replay protection.
            // A DENIED row with a payment_id does NOT block a later VERIFIED insert.
            st.execute(
                "DROP INDEX IF EXISTS idx_audit_payment"
            );
            st.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_verified_payment " +
                "ON audit_log(razorpay_payment_id) " +
                "WHERE razorpay_payment_id IS NOT NULL AND decision = 'VERIFIED'"
            );

            // Schema upgrade for existing DBs.
            addColumnIfMissing(st, "agent_policy", "reserved_paise", "BIGINT NOT NULL DEFAULT 0");

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

    /*
     * Fix #2 (atomic replay guard): atomically flip a PENDING challenge to CONSUMED
     * in the same connection/transaction as commitReservation.
     *
     * Returns true if exactly one PENDING row was consumed; false if already
     * consumed or not found (caller should return 409/400 accordingly).
     */
    public static boolean atomicConsumeChallenge(Connection conn, String orderId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE payment_challenge SET status='CONSUMED' " +
            "WHERE order_id=? AND status='PENDING'"
        )) {
            ps.setString(1, orderId);
            return ps.executeUpdate() == 1;
        }
    }

    /*
     * Fix #3 (reservation expiry): release budget held by abandoned 402 orders.
     * Called at the start of issueChallenge so stale holds don't permanently eat
     * into an agent's daily cap.
     *
     * Any PENDING challenge older than CHALLENGE_EXPIRY_MINUTES is marked EXPIRED
     * and its reserved amount is released back to the agent's pool.
     */
    public static void purgeExpiredChallenges(String agentId) {
        String cutoff = String.format(
            "datetime('now', '-%d minutes')", CHALLENGE_EXPIRY_MINUTES);

        // Collect expired challenges with their amounts first.
        List<long[]> expired = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement sel = conn.prepareStatement(
                 "SELECT order_id, amount_paise FROM payment_challenge " +
                 "WHERE agent_id=? AND status='PENDING' AND created_at < " + cutoff
             )) {
            sel.setString(1, agentId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) expired.add(new long[]{0, rs.getLong("amount_paise")});
            }
        } catch (SQLException e) {
            System.err.println("[AgentDatabase] purge query failed: " + e.getMessage());
            return;
        }

        if (expired.isEmpty()) return;

        long totalRelease = 0;
        for (long[] row : expired) totalRelease += row[1];

        // Mark them EXPIRED.
        try (Connection conn = getConnection();
             PreparedStatement upd = conn.prepareStatement(
                 "UPDATE payment_challenge SET status='EXPIRED' " +
                 "WHERE agent_id=? AND status='PENDING' AND created_at < " + cutoff
             )) {
            upd.setString(1, agentId);
            upd.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[AgentDatabase] purge update failed: " + e.getMessage());
            return;
        }

        // Release the held budget.
        if (totalRelease > 0) {
            AgentPolicyEngine.releaseReservation(agentId, totalRelease);
            System.out.printf("[AgentDatabase] Expired %d challenges for %s; released %d paise%n",
                expired.size(), agentId, totalRelease);
        }
    }

    // --- webhook deduplication ---------------------------------------------

    /*
     * Returns true if this (eventType, paymentId) pair has already been recorded.
     * The UNIQUE index on payment_event enforces idempotency at the DB level.
     */
    public static boolean recordWebhookEvent(String eventType, String paymentId, String orderId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR IGNORE INTO payment_event(event_type,payment_id,order_id) VALUES(?,?,?)"
             )) {
            ps.setString(1, eventType);
            ps.setString(2, paymentId);
            ps.setString(3, orderId);
            return ps.executeUpdate() == 1; // true = new event; false = duplicate
        } catch (SQLException e) {
            System.err.println("[AgentDatabase] recordWebhookEvent failed: " + e.getMessage());
            return false; // fail-closed: treat uncertain state as duplicate
        }
    }

    // -----------------------------------------------------------------------

    private static void addColumnIfMissing(Statement st, String table, String column, String def) {
        try { st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def); }
        catch (SQLException ignored) {} // column already exists
    }
}
