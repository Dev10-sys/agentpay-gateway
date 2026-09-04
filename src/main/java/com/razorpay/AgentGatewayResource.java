package com.razorpay;

import org.json.JSONObject;

import javax.ws.rs.GET;
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
 * AgentPay x402-inspired machine-payment gateway.
 *
 * GET /agent/resource/{id}              no proof → 402 + Razorpay order
 * GET /agent/resource/{id}              with proof headers → verify → 200
 * POST /agent/resource/{id}/verify      JSON body equivalent (curl / Postman)
 *
 * Security guarantees (Fixes #1 and #2):
 *
 *   1. Budget is reserved at challenge time, not after payment. An agent
 *      cannot pay and then be denied because the daily cap was already full.
 *
 *   2. Every 402 order is recorded in payment_challenge (order_id → agent +
 *      resource + amount). Verification fetches the live Razorpay order and
 *      compares notes.agent_id, notes.resource_id, and amount against that
 *      local record. A valid payment proof for Resource A cannot unlock
 *      Resource B.
 *
 *   3. HMAC-SHA256 verified via Razorpay SDK before any DB state changes.
 *
 *   4. Payment status confirmed "captured" live from Razorpay API.
 *
 *   5. Replay protection: payment_id can only appear once as VERIFIED in
 *      audit_log (partial unique index on razorpay_payment_id).
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
public class AgentGatewayResource {

    static final long RESOURCE_PRICE_PAISE = 100L;

    private final RazorpayClient client;
    private final String         secretKey;

    public AgentGatewayResource(String apiKey, String secretKey) {
        this.secretKey = secretKey;
        try {
            this.client = new RazorpayClient(apiKey, secretKey);
        } catch (RazorpayException e) {
            throw new RuntimeException("RazorpayClient init failed: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/resource/{resourceId}")
    public Response getResource(
            @PathParam("resourceId")              String resourceId,
            @HeaderParam("X-Agent-Id")            String agentId,
            @HeaderParam("X-Razorpay-Payment-Id") String paymentId,
            @HeaderParam("X-Razorpay-Order-Id")   String orderId,
            @HeaderParam("X-Razorpay-Signature")  String signature) {

        if (blank(agentId)) agentId = "anonymous-agent";

        boolean hasProof = !blank(paymentId) && !blank(orderId) && !blank(signature);
        return hasProof
            ? verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature)
            : issueChallenge(agentId, resourceId);
    }

    @POST
    @Path("/resource/{resourceId}/verify")
    public Response verifyDebug(@PathParam("resourceId") String resourceId, String body) {
        JSONObject j;
        try { j = new JSONObject(body); }
        catch (Exception e) { return err(400, "Invalid JSON."); }

        String agentId   = j.optString("agent_id",            "debug-agent");
        String paymentId = j.optString("razorpay_payment_id", "");
        String orderId   = j.optString("razorpay_order_id",   "");
        String signature = j.optString("razorpay_signature",  "");

        if (blank(paymentId) || blank(orderId) || blank(signature))
            return err(400, "razorpay_payment_id, razorpay_order_id and razorpay_signature required.");

        return verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature);
    }

    // ----- private ---------------------------------------------------------

    /*
     * Fix #1: Reserve budget before creating the Razorpay order.
     * If the agent can't afford it, deny here — before any money changes hands.
     * Store the challenge so verifyAndUnlock() can bind the proof to this
     * exact (agent, resource, amount) tuple.
     */
    private Response issueChallenge(String agentId, String resourceId) {
        AgentPolicyEngine.PolicyResult policy =
            AgentPolicyEngine.reserve(agentId, RESOURCE_PRICE_PAISE);

        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED, policy.reason, null, null);
            return err(403, "Agent denied: " + policy.reason);
        }

        String razorpayOrderId = null;
        try {
            Order order = client.Orders.create(new JSONObject()
                .put("amount",          RESOURCE_PRICE_PAISE)
                .put("currency",        "INR")
                .put("receipt",         "agentpay_" + resourceId + "_" + System.currentTimeMillis())
                .put("payment_capture", 1)
                .put("notes", new JSONObject()
                    .put("agent_id",    agentId)
                    .put("resource_id", resourceId)
                    .put("gateway",     "agentpay")));

            razorpayOrderId = (String) order.get("id");
            int amountPaise = (int) order.get("amount");

            // Persist the challenge so verification can bind proof to this request.
            insertChallenge(razorpayOrderId, agentId, resourceId, amountPaise);

            AuditLog.record(agentId, resourceId, amountPaise,
                    AuditLog.DECISION_APPROVED, "402 issued – " + razorpayOrderId, razorpayOrderId, null);

            return Response.status(402).entity(new JSONObject()
                .put("payment_protocol",  "agentpay/x402-inspired")
                .put("status",            "payment_required")
                .put("resource_id",       resourceId)
                .put("amount_paise",      amountPaise)
                .put("currency",          "INR")
                .put("razorpay_order_id", razorpayOrderId)
                .put("description",       "Pay to unlock: " + resourceId)
                .put("instructions",
                    "Attach X-Razorpay-Payment-Id, X-Razorpay-Order-Id, " +
                    "X-Razorpay-Signature headers and retry this GET request.")
                .toString()).build();

        } catch (RazorpayException | SQLException e) {
            // Release the reservation so the budget isn't permanently locked.
            if (razorpayOrderId == null) {
                AgentPolicyEngine.releaseReservation(agentId, RESOURCE_PRICE_PAISE);
            }
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_ERROR,
                    "Challenge issue failed: " + e.getMessage(), null, null);
            return err(502, "Order creation failed: " + e.getMessage());
        }
    }

    /*
     * Fix #2: Bind the proof to the exact challenge.
     *
     * Verification order:
     *   1. Replay check (audit_log unique index)
     *   2. HMAC-SHA256 (Razorpay SDK)
     *   3. payment.order_id matches the supplied order_id
     *   4. Fetch live order from Razorpay; compare amount, currency, notes
     *      (agent_id + resource_id) against the locally stored challenge
     *   5. Payment status == "captured"
     *   6. commitReservation (reserved → spent)
     *   7. Mark challenge CONSUMED
     */
    private Response verifyAndUnlock(String agentId, String resourceId,
                                     String paymentId, String orderId, String signature) {

        // Step 1: Replay guard.
        if (AuditLog.isAlreadyCredited(paymentId)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DUPLICATE,
                    "Replay rejected", orderId, paymentId);
            return err(409, "Payment " + paymentId + " already credited. Replay rejected.");
        }

        // Step 2: HMAC-SHA256.
        boolean sigValid;
        try {
            sigValid = Utils.verifyPaymentSignature(new JSONObject()
                .put("razorpay_payment_id", paymentId)
                .put("razorpay_order_id",   orderId)
                .put("razorpay_signature",  signature), secretKey);
        } catch (RazorpayException e) {
            return err(400, "Signature verification failed: " + e.getMessage());
        }
        if (!sigValid) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED, "HMAC mismatch", orderId, paymentId);
            return err(401, "Payment signature invalid.");
        }

        // Step 3: Fetch payment; verify payment.order_id matches supplied orderId.
        Payment payment;
        try {
            payment = client.Payments.fetch(paymentId);
        } catch (RazorpayException e) {
            return err(502, "Payments.fetch failed: " + e.getMessage());
        }

        String paymentOrderId = (String) payment.get("order_id");
        if (!orderId.equals(paymentOrderId)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "order_id mismatch: proof=" + orderId + " payment=" + paymentOrderId, orderId, paymentId);
            return err(400, "Payment order_id does not match supplied order_id.");
        }

        // Step 4: Fetch live order; verify amount, currency, and agent/resource binding.
        Order order;
        try {
            order = client.Orders.fetch(orderId);
        } catch (RazorpayException e) {
            return err(502, "Orders.fetch failed: " + e.getMessage());
        }

        int    orderAmount   = (int) order.get("amount");
        String orderCurrency = (String) order.get("currency");

        if (orderAmount != RESOURCE_PRICE_PAISE) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "Amount mismatch: order=" + orderAmount + " expected=" + RESOURCE_PRICE_PAISE,
                    orderId, paymentId);
            return err(400, "Order amount does not match resource price.");
        }
        if (!"INR".equals(orderCurrency)) {
            return err(400, "Order currency is not INR.");
        }

        // Verify notes: agent_id and resource_id must match this request.
        JSONObject notes = new JSONObject();
        try {
            Object rawNotes = order.get("notes");
            if (rawNotes instanceof JSONObject) notes = (JSONObject) rawNotes;
            else notes = new JSONObject(rawNotes.toString());
        } catch (Exception ignored) {}

        String noteAgent    = notes.optString("agent_id",    "");
        String noteResource = notes.optString("resource_id", "");

        if (!agentId.equals(noteAgent)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "agent_id mismatch: order.notes=" + noteAgent + " request=" + agentId,
                    orderId, paymentId);
            return err(403, "This order was not issued to agent: " + agentId);
        }
        if (!resourceId.equals(noteResource)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "resource_id mismatch: order.notes=" + noteResource + " request=" + resourceId,
                    orderId, paymentId);
            return err(403, "This order was not issued for resource: " + resourceId);
        }

        // Confirm local challenge exists and is still PENDING.
        try {
            String challengeStatus = getChallengeStatus(orderId);
            if (challengeStatus == null) {
                AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                        "No local challenge found for order: " + orderId, orderId, paymentId);
                return err(400, "No pending challenge found for this order.");
            }
            if (!"PENDING".equals(challengeStatus)) {
                AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                        "Challenge already consumed: " + orderId, orderId, paymentId);
                return err(409, "This order has already been consumed.");
            }
        } catch (SQLException e) {
            return err(500, "Challenge lookup failed: " + e.getMessage());
        }

        // Step 5: Payment must be captured.
        String status = (String) payment.get("status");
        if (!"captured".equals(status)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "Not captured – status: " + status, orderId, paymentId);
            return err(402, "Payment not captured. Status: " + status);
        }

        int capturedPaise = (int) payment.get("amount");

        // Step 6: Convert reservation to actual spend.
        AgentPolicyEngine.PolicyResult commit = AgentPolicyEngine.commitReservation(agentId, capturedPaise);
        if (!commit.allowed) {
            AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_ERROR,
                    "commitReservation failed: " + commit.reason, orderId, paymentId);
            return err(500, "Accounting error: " + commit.reason);
        }

        // Step 7: Mark challenge consumed and write audit record.
        try {
            consumeChallenge(orderId);
        } catch (SQLException e) {
            System.err.println("[Gateway] consumeChallenge failed: " + e.getMessage());
        }

        AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_VERIFIED,
                "Resource unlocked", orderId, paymentId);

        return Response.ok(new JSONObject()
            .put("status",       "ok")
            .put("resource_id",  resourceId)
            .put("resource",     buildResource(resourceId))
            .put("payment_id",   paymentId)
            .put("amount_paise", capturedPaise)
            .put("message",      "Access granted via AgentPay.")
            .toString()).build();
    }

    // ----- DB helpers for payment_challenge --------------------------------

    private void insertChallenge(String orderId, String agentId, String resourceId, long amountPaise)
            throws SQLException {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR IGNORE INTO payment_challenge(order_id,agent_id,resource_id,amount_paise,status)" +
                 " VALUES(?,?,?,?,'PENDING')"
             )) {
            ps.setString(1, orderId);
            ps.setString(2, agentId);
            ps.setString(3, resourceId);
            ps.setLong(4, amountPaise);
            ps.executeUpdate();
        }
    }

    private String getChallengeStatus(String orderId) throws SQLException {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM payment_challenge WHERE order_id=?"
             )) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        }
    }

    private void consumeChallenge(String orderId) throws SQLException {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE payment_challenge SET status='CONSUMED' WHERE order_id=?"
             )) {
            ps.setString(1, orderId);
            ps.executeUpdate();
        }
    }

    // ----- misc helpers ----------------------------------------------------

    private String buildResource(String resourceId) {
        return new JSONObject()
            .put("resource_id", resourceId)
            .put("content",     "Protected content for: " + resourceId)
            .put("data_points", new org.json.JSONArray()
                .put("INR settlement: \u20b9" + (RESOURCE_PRICE_PAISE / 100) + ".00")
                .put("Protocol: AgentPay/x402-inspired")
                .put("Served at: " + java.time.Instant.now()))
            .toString();
    }

    private Response err(int status, String message) {
        return Response.status(status)
            .entity(new JSONObject().put("error", true).put("message", message).toString())
            .build();
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
