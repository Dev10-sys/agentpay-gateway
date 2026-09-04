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
 * UsageMeterResource — fiat-aware micro-usage aggregation.
 *
 * Problem this solves:
 *   x402 over Ethereum lets an AI agent pay sub-cent fees on every API call.
 *   INR on Razorpay has a practical minimum order size.  An agent making 100
 *   API calls at INR 0.50 each cannot issue 100 separate bank transactions.
 *
 * Solution:
 *   Every tick increments a server-side paise counter in usage_ledger.
 *   When the running total reaches the configured threshold (default INR 5.00),
 *   a single consolidated Razorpay order is created.  The agent pays that
 *   order via Checkout and re-submits the tick with proof headers.  The server
 *   verifies the payment, resets the ledger to zero, and ticking resumes.
 *
 * Endpoint:
 *   POST /agent/meter/{resourceId}/tick
 *
 * Request headers:
 *   X-Agent-Id                        (required)
 *   X-Razorpay-Payment-Id             (only when settling a pending order)
 *   X-Razorpay-Order-Id               (only when settling)
 *   X-Razorpay-Signature              (only when settling)
 *
 * Request body (optional JSON):
 *   { "tick_paise": 50, "threshold_paise": 500 }
 *
 * Response status codes:
 *   200  tick recorded, no payment due yet
 *   402  threshold reached (body contains razorpay_order_id to pay)
 *   402  pending order exists, must settle first
 *   200  settlement confirmed, ledger reset
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

    // -------------------------------------------------------------------------
    // Public endpoint
    // -------------------------------------------------------------------------

    @POST
    @Path("/{resourceId}/tick")
    public Response tick(
            @PathParam("resourceId")              String resourceId,
            @HeaderParam("X-Agent-Id")            String agentId,
            @HeaderParam("X-Razorpay-Payment-Id") String paymentId,
            @HeaderParam("X-Razorpay-Order-Id")   String orderId,
            @HeaderParam("X-Razorpay-Signature")  String signature,
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

        // If proof headers are present this is a settlement confirmation, not a tick.
        boolean hasProof = isNotBlank(paymentId) && isNotBlank(orderId) && isNotBlank(signature);
        if (hasProof) {
            return confirmSettlement(agentId, resourceId, paymentId, orderId, signature);
        }

        return processTick(agentId, resourceId, tickPaise, thresholdPaise);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Records one usage tick.  Uses BEGIN IMMEDIATE to serialise concurrent
     * tick requests for the same (agent, resource) pair.
     *
     * If adding the tick would meet or exceed the threshold, a Razorpay
     * settlement order is created and the pending_order_id is stored in the
     * ledger.  Subsequent ticks from the same agent are blocked until that
     * order is paid and confirmed via confirmSettlement().
     */
    private Response processTick(String agentId, String resourceId,
                                 long tickPaise, long thresholdPaise) {

        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, 0);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, tickPaise,
                    AuditLog.DECISION_DENIED, "Policy denied: " + policy.reason, null, null);
            return errorResponse(403, "Agent access denied: " + policy.reason);
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
                            return errorResponse(500, "Ledger row missing after insert.");
                        }

                        long   accumulated  = rs.getLong("accumulated_paise");
                        long   threshold    = rs.getLong("threshold_paise");
                        long   tick         = rs.getLong("tick_paise");
                        String pendingOrder = rs.getString("pending_order_id");

                        // Block ticking if a settlement order is outstanding.
                        if (pendingOrder != null && !pendingOrder.isEmpty()) {
                            conn.rollback();
                            JSONObject resp = new JSONObject();
                            resp.put("status",            "settlement_pending");
                            resp.put("pending_order_id",  pendingOrder);
                            resp.put("accumulated_paise", accumulated);
                            resp.put("message",
                                "Settle the pending order before ticking further. " +
                                "Retry with X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature headers.");
                            return Response.status(402).entity(resp.toString()).build();
                        }

                        long newAccumulated = accumulated + tick;

                        if (newAccumulated >= threshold) {
                            // Threshold crossed — create the consolidated settlement order.
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
                                    "Threshold reached – settlement order: " + razorpayOrderId,
                                    razorpayOrderId, null);

                            JSONObject resp = new JSONObject();
                            resp.put("status",            "settlement_required");
                            resp.put("accumulated_paise", newAccumulated);
                            resp.put("razorpay_order_id", razorpayOrderId);
                            resp.put("message",
                                "Accumulated " + newAccumulated + " paise. Pay the consolidated order " +
                                "then retry this tick with settlement headers.");
                            return Response.status(402).entity(resp.toString()).build();
                        }

                        // Below threshold — just record the tick.
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
                                String.format("Tick: %d / %d paise", newAccumulated, threshold),
                                null, null);

                        JSONObject resp = new JSONObject();
                        resp.put("status",            "tick_accepted");
                        resp.put("accumulated_paise", newAccumulated);
                        resp.put("threshold_paise",   threshold);
                        resp.put("remaining_paise",   threshold - newAccumulated);
                        resp.put("message",           "Tick recorded. No payment due yet.");
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

    /**
     * Verifies the settlement payment and resets the ledger if valid.
     * The order_id in the proof must match the pending_order_id in the
     * ledger — stale or mismatched proofs are rejected.
     */
    private Response confirmSettlement(String agentId, String resourceId,
                                       String paymentId, String orderId, String signature) {

        // Replay guard — same payment_id cannot settle twice.
        if (AuditLog.isAlreadyCredited(paymentId)) {
            return errorResponse(409, "Payment " + paymentId + " already credited. Replay rejected.");
        }

        // HMAC-SHA256 verification.
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
                    AuditLog.DECISION_DENIED, "Settlement signature invalid.", orderId, paymentId);
            return errorResponse(401, "Settlement signature is invalid.");
        }

        // Confirm payment is captured on Razorpay.
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

        // Verify orderId matches the pending order and reset the ledger.
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
                        return errorResponse(404, "No ledger row found for this agent and resource.");
                    }
                    String pendingOrderId = rs.getString("pending_order_id");
                    if (!orderId.equals(pendingOrderId)) {
                        conn.rollback();
                        return errorResponse(400,
                            "Order ID mismatch. Expected pending order: " + pendingOrderId);
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
            return errorResponse(500, "DB error during settlement: " + e.getMessage());
        }

        // Debit the settled amount from the agent's daily budget.
        AgentPolicyEngine.checkAndDebit(agentId, capturedPaise);

        AuditLog.record(agentId, resourceId, capturedPaise,
                AuditLog.DECISION_VERIFIED,
                "Meter settlement confirmed. Ledger reset.",
                orderId, paymentId);

        JSONObject resp = new JSONObject();
        resp.put("status",       "settled");
        resp.put("amount_paise", capturedPaise);
        resp.put("message",      "Settlement confirmed. Ledger reset to zero. Ticking resumed.");
        return Response.ok(resp.toString()).build();
    }

    /**
     * Creates a Razorpay order for the consolidated settlement amount.
     * Notes on the order tag it as a meter settlement for dashboard filtering.
     */
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

    /** Inserts a ledger row if one does not already exist for (agent, resource). */
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
        return Response.status(status)
                .entity(new JSONObject().put("error", true).put("message", message).toString())
                .build();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
