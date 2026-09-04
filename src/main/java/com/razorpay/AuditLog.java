package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * AuditLog — append-only ledger of every gateway decision.
 *
 * Every request that touches a protected resource or usage meter writes
 * a row here, regardless of outcome.  This gives the operator a complete,
 * tamper-evident history of what was paid, when, by whom, and what happened.
 *
 * Replay protection:
 *   The unique index idx_audit_payment on razorpay_payment_id (created in
 *   AgentDatabase) means that calling record() twice with the same
 *   payment_id and decision=VERIFIED raises a SQLite constraint violation.
 *   isAlreadyCredited() queries that index to detect duplicates before
 *   the insert, returning a clean 409 to the caller rather than relying
 *   on the database error alone.
 */
public class AuditLog {

    public static final String DECISION_APPROVED  = "APPROVED";
    public static final String DECISION_DENIED    = "DENIED";
    public static final String DECISION_VERIFIED  = "VERIFIED";
    public static final String DECISION_DUPLICATE = "DUPLICATE";
    public static final String DECISION_ERROR     = "ERROR";

    /**
     * Appends one row to audit_log.  Failures are logged to stderr but not
     * re-thrown — a failing audit write must not break the payment flow.
     *
     * @param agentId           calling agent identifier
     * @param resourceId        resource that was requested (may be null)
     * @param amountPaise       transaction amount in paise; 0 if not applicable
     * @param decision          one of the DECISION_* constants above
     * @param reason            human-readable explanation for this decision
     * @param razorpayOrderId   Razorpay order id (nullable)
     * @param razorpayPaymentId Razorpay payment id (nullable); enforced unique
     *                          among VERIFIED rows by the database index
     */
    public static void record(String agentId, String resourceId, long amountPaise,
                              String decision, String reason,
                              String razorpayOrderId, String razorpayPaymentId) {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO audit_log" +
                 "(agent_id, resource_id, amount_paise, decision, reason," +
                 " razorpay_order_id, razorpay_payment_id, created_at)" +
                 " VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))"
             )) {
            ps.setString(1, agentId);
            ps.setString(2, resourceId);
            ps.setLong(3, amountPaise);
            ps.setString(4, decision);
            ps.setString(5, reason);
            ps.setString(6, razorpayOrderId);
            ps.setString(7, razorpayPaymentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[AuditLog] record() failed: " + e.getMessage());
        }
    }

    /**
     * Returns true if razorpayPaymentId already has a VERIFIED entry in the
     * audit log, meaning this payment has been credited before.  Used by
     * both AgentGatewayResource and UsageMeterResource to reject replays
     * before touching any ledger state.
     */
    public static boolean isAlreadyCredited(String razorpayPaymentId) {
        if (razorpayPaymentId == null || razorpayPaymentId.isEmpty()) return false;
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM audit_log " +
                 "WHERE razorpay_payment_id = ? AND decision = ? LIMIT 1"
             )) {
            ps.setString(1, razorpayPaymentId);
            ps.setString(2, DECISION_VERIFIED);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("[AuditLog] isAlreadyCredited() failed: " + e.getMessage());
            return false;
        }
    }
}
