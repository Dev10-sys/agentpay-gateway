package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * AuditLog writes an immutable record for every gateway decision.
 *
 * The unique index on razorpay_payment_id (defined in AgentDatabase) means
 * inserting the same payment_id twice raises a constraint violation, which is
 * the idempotency guard: a duplicate webhook or a replayed proof header will
 * be detected before any resource is released.
 */
public class AuditLog {

    public static final String DECISION_APPROVED  = "APPROVED";
    public static final String DECISION_DENIED    = "DENIED";
    public static final String DECISION_VERIFIED  = "VERIFIED";
    public static final String DECISION_DUPLICATE = "DUPLICATE";
    public static final String DECISION_ERROR     = "ERROR";

    /**
     * Appends a row to audit_log.
     *
     * @param agentId           calling agent identifier
     * @param resourceId        resource that was requested (nullable)
     * @param amountPaise       amount in paise (0 if not applicable)
     * @param decision          one of the DECISION_* constants
     * @param reason            human-readable explanation
     * @param razorpayOrderId   Razorpay order id (nullable)
     * @param razorpayPaymentId Razorpay payment id (nullable); must be unique
     *                          among VERIFIED rows for idempotency enforcement
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
            System.err.println("[AuditLog] Failed to write record: " + e.getMessage());
        }
    }

    /**
     * Returns true if this payment_id already has a VERIFIED entry, meaning
     * the payment has already been credited.  Used to detect replay attacks.
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
            System.err.println("[AuditLog] isAlreadyCredited check failed: " + e.getMessage());
            return false;
        }
    }
}
