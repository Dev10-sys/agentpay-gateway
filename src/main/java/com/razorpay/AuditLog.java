package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*
 * Append-only ledger of every gateway decision.
 *
 * The unique index on razorpay_payment_id (created in AgentDatabase) means
 * a payment can only appear once with decision=VERIFIED. isAlreadyCredited()
 * checks this before any resource is released, giving a clean 409 on replay.
 */
public class AuditLog {

    public static final String DECISION_APPROVED  = "APPROVED";
    public static final String DECISION_DENIED    = "DENIED";
    public static final String DECISION_VERIFIED  = "VERIFIED";
    public static final String DECISION_DUPLICATE = "DUPLICATE";
    public static final String DECISION_ERROR     = "ERROR";

    // Failures are swallowed so a broken audit write never kills the payment flow.
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
