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
                "  event_id    TEXT," +
                "  event_type  TEXT NOT NULL," +
                "  payment_id  TEXT NOT NULL," +
                "  order_id    TEXT," +
                "  received_at TEXT NOT NULL DEFAULT (datetime('now'))" +
                ")"
            );

            st.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_event_id " +
                "ON payment_event(event_id) WHERE event_id IS NOT NULL"
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

            // Schema retrofitting for existing DBs.
            addColumnIfMissing(st, "agent_policy", "reserved_paise", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "agent_policy", "last_reset_date", "TEXT NOT NULL DEFAULT (date('now'))");
            addColumnIfMissing(st, "payment_event", "event_id", "TEXT");
            addColumnIfMissing(st, "payment_challenge", "status", "TEXT NOT NULL DEFAULT 'PENDING'");
            addColumnIfMissing(st, "payment_challenge", "amount_paise", "BIGINT NOT NULL DEFAULT 100");
            addColumnIfMissing(st, "usage_ledger", "pending_order_id", "TEXT");

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
     * Fix #3 (atomic reservation expiry): release budget held by abandoned 402 orders.
     * Runs inside a single BEGIN IMMEDIATE transaction so selecting expired challenges,
     * updating their status to EXPIRED, and releasing the reserved amount happen atomically
     * without any race with concurrent verifications.
     */
    public static void purgeExpiredChallenges(String agentId) {
        String cutoff = String.format(
            "datetime('now', '-%d minutes')", CHALLENGE_EXPIRY_MINUTES);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            long totalRelease = 0;
            int count = 0;
            try (PreparedStatement sel = conn.prepareStatement(
                "SELECT amount_paise FROM payment_challenge " +
                "WHERE agent_id=? AND status='PENDING' AND created_at < " + cutoff
            )) {
                sel.setString(1, agentId);
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        totalRelease += rs.getLong("amount_paise");
                        count++;
                    }
                }
            }

            if (count > 0) {
                try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE payment_challenge SET status='EXPIRED' " +
                    "WHERE agent_id=? AND status='PENDING' AND created_at < " + cutoff
                )) {
                    upd.setString(1, agentId);
                    upd.executeUpdate();
                }

                if (totalRelease > 0) {
                    try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE agent_policy SET reserved_paise=MAX(0,reserved_paise-?) WHERE agent_id=?"
                    )) {
                        upd.setLong(1, totalRelease);
                        upd.setString(2, agentId);
                        upd.executeUpdate();
                    }
                }
            }

            conn.commit();
            if (totalRelease > 0) {
                System.out.printf("[AgentDatabase] Expired %d challenges for %s; released %d paise%n",
                    count, agentId, totalRelease);
            }
        } catch (SQLException e) {
            System.err.println("[AgentDatabase] purgeExpiredChallenges failed: " + e.getMessage());
        }
    }

    // --- webhook deduplication ---------------------------------------------

    public enum WebhookRecordResult {
        NEW,
        DUPLICATE,
        ERROR
    }

    /*
     * Idempotently records an incoming webhook event.
     * Distinguishes between NEW events, DUPLICATE events, and DB ERRORS
     * so delivery failures return HTTP 500 to signal Razorpay retry.
     */
    public static WebhookRecordResult recordWebhookEvent(String eventId, String eventType, String paymentId, String orderId) {
        boolean hasEventId = eventId != null && !eventId.trim().isEmpty();
        String sql = hasEventId
            ? "INSERT INTO payment_event(event_id,event_type,payment_id,order_id) VALUES(?,?,?,?)"
            : "INSERT INTO payment_event(event_type,payment_id,order_id) VALUES(?,?,?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (hasEventId) {
                ps.setString(1, eventId.trim());
                ps.setString(2, eventType);
                ps.setString(3, paymentId);
                ps.setString(4, orderId);
            } else {
                ps.setString(1, eventType);
                ps.setString(2, paymentId);
                ps.setString(3, orderId);
            }
            int rows = ps.executeUpdate();
            return rows == 1 ? WebhookRecordResult.NEW : WebhookRecordResult.DUPLICATE;
        } catch (SQLException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("constraint")) {
                return WebhookRecordResult.DUPLICATE;
            }
            System.err.println("[AgentDatabase] recordWebhookEvent DB error: " + e.getMessage());
            return WebhookRecordResult.ERROR;
        }
    }

    public static boolean recordWebhookEvent(String eventType, String paymentId, String orderId) {
        return recordWebhookEvent(null, eventType, paymentId, orderId) == WebhookRecordResult.NEW;
    }

    // -----------------------------------------------------------------------

    private static void addColumnIfMissing(Statement st, String table, String column, String def) {
        try { st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def); }
        catch (SQLException ignored) {} // column already exists
    }
}
