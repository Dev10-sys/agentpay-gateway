package com.razorpay;

import org.json.JSONObject;

import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/*
 * Micro-usage metering for INR payments.
 *
 * Problem: Razorpay minimum order size makes per-call billing impractical.
 * Solution: ticks accumulate in a SQLite ledger per (agent, resource).
 *   When the running total crosses the threshold, one consolidated Razorpay
 *   order fires. Agent pays it, confirms with proof headers, ledger resets.
 *
 * POST /agent/meter/{resourceId}/tick
 *   Without proof  → record tick, or return 402 when threshold is crossed
 *   With proof      → validate + settle the pending order, reset ledger
 *
 * Fix #3: captured amount must equal accumulated_paise before resetting.
 * Fix #5: tick_paise and threshold_paise are validated server-side.
 *         Negative or zero values are rejected. Threshold minimum is 100 paise.
 */
@Path("/agent/meter")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UsageMeterResource {

    // Server-side defaults; clients may override only within validated bounds.
    static final long MIN_TICK_PAISE      = 1L;
    static final long MAX_TICK_PAISE      = 100_00L;  // ₹100
    static final long MIN_THRESHOLD_PAISE = 100L;
    static final long MAX_THRESHOLD_PAISE = 10_000_00L; // ₹10,000

    private static final long DEFAULT_TICK      = 50L;
    private static final long DEFAULT_THRESHOLD = 500L;

    private final RazorpayClient client;
    private final String         secretKey;

    public UsageMeterResource(String apiKey, String secretKey) {
        this.secretKey = secretKey;
        try {
            this.client = new RazorpayClient(apiKey, secretKey);
        } catch (RazorpayException e) {
            throw new RuntimeException("RazorpayClient init failed: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/{resourceId}/tick")
    public Response tick(
            @PathParam("resourceId")              String resourceId,
            @HeaderParam("X-Agent-Id")            String agentId,
            @HeaderParam("X-Razorpay-Payment-Id") String paymentId,
            @HeaderParam("X-Razorpay-Order-Id")   String orderId,
            @HeaderParam("X-Razorpay-Signature")  String signature,
            String body) {

        if (blank(agentId)) agentId = "anonymous-agent";

        // Parse and validate client-supplied pricing overrides (Fix #5).
        long tickPaise = DEFAULT_TICK, thresholdPaise = DEFAULT_THRESHOLD;
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject req = new JSONObject(body);
                if (req.has("tick_paise"))      tickPaise      = req.getLong("tick_paise");
                if (req.has("threshold_paise")) thresholdPaise = req.getLong("threshold_paise");
            } catch (Exception ignored) {}
        }

        // Bounds validation — reject nonsensical pricing from any caller.
        if (tickPaise < MIN_TICK_PAISE || tickPaise > MAX_TICK_PAISE)
            return err(400, "tick_paise must be between " + MIN_TICK_PAISE + " and " + MAX_TICK_PAISE + ".");
        if (thresholdPaise < MIN_THRESHOLD_PAISE || thresholdPaise > MAX_THRESHOLD_PAISE)
            return err(400, "threshold_paise must be between " + MIN_THRESHOLD_PAISE + " and " + MAX_THRESHOLD_PAISE + ".");
        if (tickPaise > thresholdPaise)
            return err(400, "tick_paise cannot exceed threshold_paise.");

        boolean hasProof = !blank(paymentId) && !blank(orderId) && !blank(signature);
        return hasProof
            ? confirmSettlement(agentId, resourceId, paymentId, orderId, signature, thresholdPaise)
            : processTick(agentId, resourceId, tickPaise, thresholdPaise);
    }

    // ----- private ---------------------------------------------------------

    private Response processTick(String agentId, String resourceId,
                                 long tickPaise, long thresholdPaise) {

        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, 0);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, tickPaise, AuditLog.DECISION_DENIED,
                    "Policy denied: " + policy.reason, null, null);
            return err(403, "Agent denied: " + policy.reason);
        }

        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            beginImmediate(conn);
            try {
                ensureLedgerRow(conn, agentId, resourceId, thresholdPaise, tickPaise);

                try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT accumulated_paise,threshold_paise,tick_paise,pending_order_id " +
                    "FROM usage_ledger WHERE agent_id=? AND resource_id=?"
                )) {
                    sel.setString(1, agentId);
                    sel.setString(2, resourceId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return err(500, "Ledger row missing."); }

                        long   acc     = rs.getLong("accumulated_paise");
                        long   thresh  = rs.getLong("threshold_paise");
                        long   tick    = rs.getLong("tick_paise");
                        String pending = rs.getString("pending_order_id");

                        if (!blank(pending)) {
                            conn.rollback();
                            return Response.status(402).entity(new JSONObject()
                                .put("status",            "settlement_pending")
                                .put("pending_order_id",  pending)
                                .put("accumulated_paise", acc)
                                .put("message",
                                    "Settle the pending order first. Retry with " +
                                    "X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature.")
                                .toString()).build();
                        }

                        long newAcc = acc + tick;

                        if (newAcc >= thresh) {
                            String settlementOrderId;
                            try {
                                settlementOrderId = createSettlementOrder(agentId, resourceId, newAcc);
                            } catch (RazorpayException e) {
                                conn.rollback();
                                return err(502, "Settlement order creation failed: " + e.getMessage());
                            }

                            try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE usage_ledger SET accumulated_paise=?,pending_order_id=? " +
                                "WHERE agent_id=? AND resource_id=?"
                            )) {
                                upd.setLong(1, newAcc);
                                upd.setString(2, settlementOrderId);
                                upd.setString(3, agentId);
                                upd.setString(4, resourceId);
                                upd.executeUpdate();
                            }
                            conn.commit();
                            AuditLog.record(agentId, resourceId, newAcc, AuditLog.DECISION_APPROVED,
                                    "Threshold – settlement order: " + settlementOrderId, settlementOrderId, null);
                            return Response.status(402).entity(new JSONObject()
                                .put("status",            "settlement_required")
                                .put("accumulated_paise", newAcc)
                                .put("razorpay_order_id", settlementOrderId)
                                .put("message", "Pay the consolidated order, then retry with settlement headers.")
                                .toString()).build();
                        }

                        try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE usage_ledger SET accumulated_paise=? WHERE agent_id=? AND resource_id=?"
                        )) {
                            upd.setLong(1, newAcc);
                            upd.setString(2, agentId);
                            upd.setString(3, resourceId);
                            upd.executeUpdate();
                        }
                        conn.commit();
                        AuditLog.record(agentId, resourceId, tick, AuditLog.DECISION_APPROVED,
                                String.format("Tick: %d/%d paise", newAcc, thresh), null, null);

                        return Response.ok(new JSONObject()
                            .put("status",            "tick_accepted")
                            .put("accumulated_paise", newAcc)
                            .put("threshold_paise",   thresh)
                            .put("remaining_paise",   thresh - newAcc)
                            .put("message",           "Tick recorded.")
                            .toString()).build();
                    }
                }
            } catch (Exception e) {
                rollback(conn); return err(500, "Tick failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            return err(500, "DB error: " + e.getMessage());
        }
    }

    /*
     * Fix #3: reject settlement if captured amount != accumulated amount.
     * This prevents a partial payment from resetting the full ledger.
     */
    private Response confirmSettlement(String agentId, String resourceId,
                                       String paymentId, String orderId, String signature,
                                       long thresholdPaise) {

        if (AuditLog.isAlreadyCredited(paymentId))
            return err(409, "Payment " + paymentId + " already credited. Replay rejected.");

        boolean sigValid;
        try {
            sigValid = Utils.verifyPaymentSignature(new JSONObject()
                .put("razorpay_payment_id", paymentId)
                .put("razorpay_order_id",   orderId)
                .put("razorpay_signature",  signature), secretKey);
        } catch (RazorpayException e) {
            return err(400, "Sig verify error: " + e.getMessage());
        }
        if (!sigValid) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "Settlement sig invalid", orderId, paymentId);
            return err(401, "Settlement signature invalid.");
        }

        Payment payment;
        try { payment = client.Payments.fetch(paymentId); }
        catch (RazorpayException e) { return err(502, "Payment fetch failed: " + e.getMessage()); }

        String status = (String) payment.get("status");
        if (!"captured".equals(status)) return err(402, "Payment not captured. Status: " + status);

        int capturedPaise = (int) payment.get("amount");

        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            beginImmediate(conn);
            try {
                long accumulated;
                String pendingOrder;

                try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT accumulated_paise,pending_order_id " +
                    "FROM usage_ledger WHERE agent_id=? AND resource_id=?"
                )) {
                    sel.setString(1, agentId);
                    sel.setString(2, resourceId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return err(404, "No ledger row found."); }
                        accumulated  = rs.getLong("accumulated_paise");
                        pendingOrder = rs.getString("pending_order_id");
                    }
                }

                if (!orderId.equals(pendingOrder)) {
                    conn.rollback();
                    return err(400, "Order ID mismatch. Expected: " + pendingOrder);
                }

                // Fix #3: captured must equal what we accumulated.
                if (capturedPaise != accumulated) {
                    conn.rollback();
                    AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_DENIED,
                            "Amount mismatch: captured=" + capturedPaise + " expected=" + accumulated,
                            orderId, paymentId);
                    return err(400, String.format(
                        "Captured amount (%d paise) does not match accumulated balance (%d paise). " +
                        "Ledger not reset.", capturedPaise, accumulated));
                }

                try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE usage_ledger SET accumulated_paise=0,pending_order_id=NULL " +
                    "WHERE agent_id=? AND resource_id=?"
                )) {
                    upd.setString(1, agentId);
                    upd.setString(2, resourceId);
                    upd.executeUpdate();
                }
                conn.commit();

            } catch (SQLException e) {
                rollback(conn); return err(500, "DB error during settlement: " + e.getMessage());
            }
        } catch (SQLException e) {
            return err(500, "DB error: " + e.getMessage());
        }

        // Debit the daily budget for the settled amount.
        AgentPolicyEngine.PolicyResult debit = AgentPolicyEngine.checkAndDebit(agentId, capturedPaise);
        if (!debit.allowed) {
            // Ledger is already reset at this point. Log the inconsistency but don't fail the response —
            // the payment is real and captured. The daily cap for metering is advisory here.
            System.err.println("[Meter] policy debit failed after settlement: " + debit.reason);
        }

        AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_VERIFIED,
                "Meter settled. Ledger reset.", orderId, paymentId);

        return Response.ok(new JSONObject()
            .put("status",       "settled")
            .put("amount_paise", capturedPaise)
            .put("message",      "Settlement confirmed. Ledger reset to zero. Ticking resumed.")
            .toString()).build();
    }

    private String createSettlementOrder(String agentId, String resourceId, long amountPaise)
            throws RazorpayException {
        Order order = client.Orders.create(new JSONObject()
            .put("amount",          amountPaise)
            .put("currency",        "INR")
            .put("receipt",         "meter_" + resourceId + "_" + System.currentTimeMillis())
            .put("payment_capture", 1)
            .put("notes", new JSONObject()
                .put("agent_id",    agentId)
                .put("resource_id", resourceId)
                .put("type",        "usage_meter_settlement")));
        return (String) order.get("id");
    }

    private void ensureLedgerRow(Connection conn, String agentId, String resourceId,
                                 long thresholdPaise, long tickPaise) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO usage_ledger" +
            "(agent_id,resource_id,accumulated_paise,threshold_paise,tick_paise) VALUES(?,?,0,?,?)"
        )) {
            ps.setString(1, agentId);
            ps.setString(2, resourceId);
            ps.setLong(3, thresholdPaise);
            ps.setLong(4, tickPaise);
            ps.executeUpdate();
        }
    }

    private void beginImmediate(Connection conn) {
        try (java.sql.Statement st = conn.createStatement()) { st.execute("BEGIN IMMEDIATE"); }
        catch (SQLException ignored) {}
    }

    private void rollback(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) {}
    }

    private Response err(int status, String message) {
        return Response.status(status)
            .entity(new JSONObject().put("error", true).put("message", message).toString())
            .build();
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
