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
 * Micro-usage metering for INR micro-payments.
 *
 * Problem: Razorpay has a minimum viable order size. An agent calling an API
 * 100 times at ₹0.50 each can't create 100 separate bank transactions.
 *
 * Solution: ticks accumulate in a SQLite ledger. Once the running total
 * crosses the threshold, one consolidated Razorpay order fires. Agent pays
 * it, confirms with proof headers, ledger resets to zero.
 *
 * POST /agent/meter/{resourceId}/tick
 *
 * Headers: X-Agent-Id (required)
 *          X-Razorpay-Payment-Id / Order-Id / Signature  — only when settling
 *
 * Body (optional): { "tick_paise": 50, "threshold_paise": 500 }
 */
@Path("/agent/meter")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UsageMeterResource {

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

        if (agentId == null || agentId.trim().isEmpty()) agentId = "anonymous-agent";

        long tickPaise = DEFAULT_TICK, thresholdPaise = DEFAULT_THRESHOLD;
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject req = new JSONObject(body);
                if (req.has("tick_paise"))      tickPaise      = req.getLong("tick_paise");
                if (req.has("threshold_paise")) thresholdPaise = req.getLong("threshold_paise");
            } catch (Exception ignored) {}
        }

        boolean hasProof = isNotBlank(paymentId) && isNotBlank(orderId) && isNotBlank(signature);
        return hasProof
            ? confirmSettlement(agentId, resourceId, paymentId, orderId, signature)
            : processTick(agentId, resourceId, tickPaise, thresholdPaise);
    }

    // ----- private ---------------------------------------------------------

    private Response processTick(String agentId, String resourceId, long tickPaise, long thresholdPaise) {
        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, 0);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, tickPaise, AuditLog.DECISION_DENIED,
                    "Policy denied: " + policy.reason, null, null);
            return err(403, "Agent denied: " + policy.reason);
        }

        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException ignored) {}

            try {
                ensureLedgerRow(conn, agentId, resourceId, thresholdPaise, tickPaise);

                try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT accumulated_paise, threshold_paise, tick_paise, pending_order_id " +
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

                        if (pending != null && !pending.isEmpty()) {
                            conn.rollback();
                            return Response.status(402).entity(new JSONObject()
                                .put("status",            "settlement_pending")
                                .put("pending_order_id",  pending)
                                .put("accumulated_paise", acc)
                                .put("message",
                                    "Settle the pending order before ticking. " +
                                    "Retry with X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature.")
                                .toString()).build();
                        }

                        long newAcc = acc + tick;

                        if (newAcc >= thresh) {
                            String settlementOrderId = createSettlementOrder(agentId, resourceId, newAcc);
                            try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE usage_ledger SET accumulated_paise=?, pending_order_id=? " +
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
                                .put("message", "Pay the consolidated order then retry with settlement headers.")
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
                            .put("message",           "Tick recorded. No payment due yet.")
                            .toString()).build();
                    }
                }
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                return err(500, "Tick failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            return err(500, "DB error: " + e.getMessage());
        }
    }

    private Response confirmSettlement(String agentId, String resourceId,
                                       String paymentId, String orderId, String signature) {
        if (AuditLog.isAlreadyCredited(paymentId)) {
            return err(409, "Payment " + paymentId + " already credited. Replay rejected.");
        }

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
        try {
            payment = client.Payments.fetch(paymentId);
        } catch (RazorpayException e) {
            return err(502, "Payment fetch failed: " + e.getMessage());
        }

        String status = (String) payment.get("status");
        if (!"captured".equals(status)) return err(402, "Payment not captured. Status: " + status);

        int capturedPaise = (int) payment.get("amount");

        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException ignored) {}

            try (PreparedStatement sel = conn.prepareStatement(
                "SELECT pending_order_id FROM usage_ledger WHERE agent_id=? AND resource_id=?"
            )) {
                sel.setString(1, agentId);
                sel.setString(2, resourceId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return err(404, "No ledger row found."); }
                    String pendingOrder = rs.getString("pending_order_id");
                    if (!orderId.equals(pendingOrder)) {
                        conn.rollback();
                        return err(400, "Order ID mismatch. Expected: " + pendingOrder);
                    }
                }
            }

            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE usage_ledger SET accumulated_paise=0, pending_order_id=NULL " +
                "WHERE agent_id=? AND resource_id=?"
            )) {
                upd.setString(1, agentId);
                upd.setString(2, resourceId);
                upd.executeUpdate();
            }
            conn.commit();

        } catch (SQLException e) {
            return err(500, "DB error during settlement: " + e.getMessage());
        }

        AgentPolicyEngine.checkAndDebit(agentId, capturedPaise);
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

    private Response err(int status, String message) {
        return Response.status(status)
            .entity(new JSONObject().put("error", true).put("message", message).toString())
            .build();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
