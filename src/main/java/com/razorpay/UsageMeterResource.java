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

/**
 * UsageMeterResource — fiat-aware usage aggregation, AgentPay's primary differentiator.
 *
 * Why this exists: x402 over crypto allows sub-cent micro-payments per API call.
 * INR bank rails have a practical minimum transaction size (~₹1).  Rather than
 * refusing micro-usage, AgentPay accumulates small per-tick charges in a server-side
 * ledger.  When the running total crosses a configurable threshold, one consolidated
 * Razorpay order fires.  The ledger resets only after that order is confirmed
 * captured — never on failure or pending state.
 *
 * Endpoint:
 *   POST /agent/meter/{resourceId}/tick
 *
 * Headers:
 *   X-Agent-Id                         – calling agent identifier
 *   X-Razorpay-Payment-Id  (optional)  – provide when settling a pending order
 *   X-Razorpay-Order-Id    (optional)
 *   X-Razorpay-Signature   (optional)
 *
 * Body: optional JSON { "tick_paise": N, "threshold_paise": N }
 */
@Path("/agent/meter")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UsageMeterResource {

    private static final long DEFAULT_TICK_PAISE      = 50L;
    private static final long DEFAULT_THRESHOLD_PAISE = 500L;

    private final RazorpayClient client;
    private final String         secretKey;

    public UsageMeterResource(String apiKey, String secretKey) {
        this.secretKey = secretKey;
        try {
            this.client = new RazorpayClient(apiKey, secretKey);
        } catch (RazorpayException e) {
            throw new RuntimeException("Failed to initialise RazorpayClient: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/{resourceId}/tick")
    public Response tick(
            @PathParam("resourceId")                 String resourceId,
            @HeaderParam("X-Agent-Id")               String agentId,
            @HeaderParam("X-Razorpay-Payment-Id")    String paymentId,
            @HeaderParam("X-Razorpay-Order-Id")      String orderId,
            @HeaderParam("X-Razorpay-Signature")     String signature,
            String body) {

        if (agentId == null || agentId.trim().isEmpty()) {
            agentId = "anonymous-agent";
        }

        long tickPaise      = DEFAULT_TICK_PAISE;
        long thresholdPaise = DEFAULT_THRESHOLD_PAISE;

        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject req = new JSONObject(body);
                if (req.has("tick_paise"))      tickPaise      = req.getLong("tick_paise");
                if (req.has("threshold_paise")) thresholdPaise = req.getLong("threshold_paise");
            } catch (Exception ignored) {}
        }

        boolean hasSettlementProof = isNotBlank(paymentId) && isNotBlank(orderId) && isNotBlank(signature);

        if (hasSettlementProof) {
            return confirmSettlement(agentId, resourceId, paymentId, orderId, signature);
        }

        return processTick(agentId, resourceId, tickPaise, thresholdPaise);
    }

    private Response processTick(String agentId, String resourceId,
                                  long tickPaise, long thresholdPaise) {

        AgentPolicyEngine.PolicyResult policy =
                AgentPolicyEngine.checkAndDebit(agentId, 0);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, tickPaise,
                    AuditLog.DECISION_DENIED, "Policy denied before tick: " + policy.reason,
                    null, null);
            return errorResponse(403, policy.reason);
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
                    "FROM usage_ledger WHERE agent_id = ? AND resource_id = ?"
                )) {
                    sel.setString(1, agentId);
                    sel.setString(2, resourceId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return errorResponse(500, "Ledger row missing after insert");
                        }

                        long accumulated   = rs.getLong("accumulated_paise");
                        long threshold     = rs.getLong("threshold_paise");
                        long tick          = rs.getLong("tick_paise");
                        String pendingOrder = rs.getString("pending_order_id");

                        if (pendingOrder != null && !pendingOrder.isEmpty()) {
                            conn.rollback();
                            JSONObject resp = new JSONObject();
                            resp.put("status",            "settlement_pending");
                            resp.put("pending_order_id",  pendingOrder);
                            resp.put("accumulated_paise", accumulated);
                            resp.put("message",           "Settle the pending order before ticking further. " +
                                                          "Retry with X-Razorpay-Payment-Id, " +
                                                          "X-Razorpay-Order-Id, X-Razorpay-Signature headers.");
                            return Response.status(402).entity(resp.toString()).build();
                        }

                        long newAccumulated = accumulated + tick;

                        if (newAccumulated >= threshold) {
                            String razorpayOrderId = createSettlementOrder(agentId, resourceId, newAccumulated);

                            try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE usage_ledger " +
                                "SET accumulated_paise = ?, pending_order_id = ? " +
                                "WHERE agent_id = ? AND resource_id = ?"
                            )) {
                                upd.setLong(1,   newAccumulated);
                                upd.setString(2, razorpayOrderId);
                                upd.setString(3, agentId);
                                upd.setString(4, resourceId);
                                upd.executeUpdate();
                            }

                            conn.commit();

                            AuditLog.record(agentId, resourceId, newAccumulated,
                                    AuditLog.DECISION_APPROVED,
                                    "Threshold crossed – settlement order created",
                                    razorpayOrderId, null);

                            JSONObject resp = new JSONObject();
                            resp.put("status",            "settlement_required");
                            resp.put("accumulated_paise", newAccumulated);
                            resp.put("razorpay_order_id", razorpayOrderId);
                            resp.put("message",
                                    "Accumulated " + newAccumulated + " paise crossed the threshold of " +
                                    threshold + " paise. Pay this consolidated order and confirm by " +
                                    "reticking with settlement headers.");
                            return Response.status(402).entity(resp.toString()).build();
                        }

                        try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE usage_ledger SET accumulated_paise = ? " +
                            "WHERE agent_id = ? AND resource_id = ?"
                        )) {
                            upd.setLong(1,   newAccumulated);
                            upd.setString(2, agentId);
                            upd.setString(3, resourceId);
                            upd.executeUpdate();
                        }

                        conn.commit();

                        AuditLog.record(agentId, resourceId, tick,
                                AuditLog.DECISION_APPROVED,
                                String.format("Tick accepted. Accumulated: %d / %d paise",
                                        newAccumulated, threshold),
                                null, null);

                        JSONObject resp = new JSONObject();
                        resp.put("status",             "tick_accepted");
                        resp.put("accumulated_paise",  newAccumulated);
                        resp.put("threshold_paise",    threshold);
                        resp.put("remaining_paise",    threshold - newAccumulated);
                        resp.put("message",            "Tick recorded. No payment due yet.");
                        return Response.ok(resp.toString()).build();
                    }
                }
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                return errorResponse(500, "Tick processing failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            return errorResponse(500, "DB connection failed: " + e.getMessage());
        }
    }

    private Response confirmSettlement(String agentId, String resourceId,
                                       String paymentId, String orderId, String signature) {

        if (AuditLog.isAlreadyCredited(paymentId)) {
            return errorResponse(409, "Payment " + paymentId + " already credited. Replay rejected.");
        }

        JSONObject sigOpts = new JSONObject();
        sigOpts.put("razorpay_payment_id", paymentId);
        sigOpts.put("razorpay_order_id",   orderId);
        sigOpts.put("razorpay_signature",  signature);

        boolean sigValid;
        try {
            sigValid = Utils.verifyPaymentSignature(sigOpts, secretKey);
        } catch (RazorpayException e) {
            return errorResponse(400, "Signature verification error: " + e.getMessage());
        }

        if (!sigValid) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DENIED, "Meter settlement: signature invalid",
                    orderId, paymentId);
            return errorResponse(401, "Settlement signature invalid.");
        }

        Payment payment;
        try {
            payment = client.Payments.fetch(paymentId);
        } catch (RazorpayException e) {
            return errorResponse(502, "Payment fetch failed: " + e.getMessage());
        }

        String status = (String) payment.get("status");
        if (!"captured".equals(status)) {
            return errorResponse(402, "Settlement payment not captured. Status: " + status);
        }

        int capturedPaise = (int) payment.get("amount");

        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException ignored) {}

            try (PreparedStatement sel = conn.prepareStatement(
                "SELECT pending_order_id FROM usage_ledger " +
                "WHERE agent_id = ? AND resource_id = ?"
            )) {
                sel.setString(1, agentId);
                sel.setString(2, resourceId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return errorResponse(404, "No ledger row found for this agent+resource.");
                    }
                    String pendingOrderId = rs.getString("pending_order_id");
                    if (!orderId.equals(pendingOrderId)) {
                        conn.rollback();
                        return errorResponse(400,
                                "Order ID mismatch. Expected: " + pendingOrderId);
                    }
                }
            }

            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE usage_ledger " +
                "SET accumulated_paise = 0, pending_order_id = NULL " +
                "WHERE agent_id = ? AND resource_id = ?"
            )) {
                upd.setString(1, agentId);
                upd.setString(2, resourceId);
                upd.executeUpdate();
            }

            conn.commit();

        } catch (SQLException e) {
            return errorResponse(500, "DB error during settlement confirmation: " + e.getMessage());
        }

        AgentPolicyEngine.checkAndDebit(agentId, capturedPaise);

        AuditLog.record(agentId, resourceId, capturedPaise,
                AuditLog.DECISION_VERIFIED,
                "Meter settlement confirmed. Ledger reset to zero.",
                orderId, paymentId);

        JSONObject resp = new JSONObject();
        resp.put("status",         "settled");
        resp.put("amount_paise",   capturedPaise);
        resp.put("message",        "Settlement confirmed. Ledger reset. Ticking resumed.");
        return Response.ok(resp.toString()).build();
    }

    private String createSettlementOrder(String agentId, String resourceId, long amountPaise)
            throws RazorpayException {
        JSONObject opts = new JSONObject();
        opts.put("amount",          amountPaise);
        opts.put("currency",        "INR");
        opts.put("receipt",         "meter_" + resourceId + "_" + System.currentTimeMillis());
        opts.put("payment_capture", 1);

        JSONObject notes = new JSONObject();
        notes.put("agent_id",    agentId);
        notes.put("resource_id", resourceId);
        notes.put("type",        "usage_meter_settlement");
        opts.put("notes", notes);

        Order order = client.Orders.create(opts);
        return (String) order.get("id");
    }

    private void ensureLedgerRow(Connection conn, String agentId, String resourceId,
                                  long thresholdPaise, long tickPaise) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO usage_ledger" +
            "(agent_id, resource_id, accumulated_paise, threshold_paise, tick_paise)" +
            " VALUES (?, ?, 0, ?, ?)"
        )) {
            ps.setString(1, agentId);
            ps.setString(2, resourceId);
            ps.setLong(3, thresholdPaise);
            ps.setLong(4, tickPaise);
            ps.executeUpdate();
        }
    }

    private Response errorResponse(int status, String message) {
        JSONObject body = new JSONObject();
        body.put("error",   true);
        body.put("message", message);
        return Response.status(status).entity(body.toString()).build();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
