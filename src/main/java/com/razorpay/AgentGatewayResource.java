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
 * GET /agent/resource/{id}            no proof → 402 + Razorpay order
 * GET /agent/resource/{id}            with proof headers → verify → 200
 * POST /agent/resource/{id}/verify    JSON body equivalent (curl / Postman)
 *
 * Security guarantees:
 *
 *   1. Budget reserved atomically before the Razorpay order is created.
 *      An agent over its daily cap is denied before any payment is attempted.
 *      Abandoned reservations are released automatically after 15 minutes.
 *
 *   2. Every 402 order is stored in payment_challenge (order_id → agent +
 *      resource + amount). Verification fetches the live Razorpay order and
 *      compares notes + amount against that record.
 *
 *   3. Challenge consumption is atomic: a single UPDATE WHERE status='PENDING'
 *      runs inside the same BEGIN IMMEDIATE block as the reservation commit.
 *      If two concurrent requests race on the same proof, only one UPDATE
 *      affects a row; the other gets 0 rows → 409.
 *
 *   4. HMAC-SHA256 verified via Razorpay SDK.
 *
 *   5. Replay protection: partial unique index on audit_log limits VERIFIED
 *      rows to one per payment_id.
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

    private Response issueChallenge(String agentId, String resourceId) {
        // Release any reservations from orders the agent never paid (15-min window).
        AgentDatabase.purgeExpiredChallenges(agentId);

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
                .put("receipt",         receipt("agp_", resourceId))
                .put("payment_capture", 1)
                .put("notes", new JSONObject()
                    .put("agent_id",    agentId)
                    .put("resource_id", resourceId)
                    .put("gateway",     "agentpay")));

            razorpayOrderId = (String) order.get("id");
            int amountPaise = (int) order.get("amount");

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
            if (razorpayOrderId == null) {
                // Order was never created; safe to release the reservation.
                AgentPolicyEngine.releaseReservation(agentId, RESOURCE_PRICE_PAISE);
            }
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_ERROR,
                    "Challenge issue failed: " + e.getMessage(), null, null);
            return err(502, "Order creation failed: " + e.getMessage());
        }
    }

    /*
     * Fix #2 (atomic replay guard):
     *
     * Challenge consumption and reservation commit happen inside ONE
     * BEGIN IMMEDIATE transaction. Two concurrent requests with the same
     * proof will both reach atomicConsumeChallenge(); only one UPDATE
     * matches (status='PENDING'), the other gets 0 rows and returns 409.
     *
     * Verification order:
     *   1. HMAC-SHA256
     *   2. payment.order_id == supplied order_id
     *   3. Live order: amount, currency, notes (agent + resource)
     *   4. Payment status == "captured"
     *   5. BEGIN IMMEDIATE → atomicConsumeChallenge → commitReservation → COMMIT
     *   6. Insert VERIFIED audit row
     */
    private Response verifyAndUnlock(String agentId, String resourceId,
                                     String paymentId, String orderId, String signature) {

        // Step 1: HMAC-SHA256.
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

        // Step 2: Fetch payment; verify payment.order_id == supplied orderId.
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

        // Step 3: Fetch live order; verify amount, currency, and notes binding.
        Order order;
        try {
            order = client.Orders.fetch(orderId);
        } catch (RazorpayException e) {
            return err(502, "Orders.fetch failed: " + e.getMessage());
        }

        int    orderAmount   = (int) order.get("amount");
        String orderCurrency = (String) order.get("currency");

        if (orderAmount != RESOURCE_PRICE_PAISE)
            return err(400, "Order amount " + orderAmount + " != expected " + RESOURCE_PRICE_PAISE + " paise.");
        if (!"INR".equals(orderCurrency))
            return err(400, "Order currency is not INR.");

        JSONObject notes = extractNotes(order);
        String noteAgent    = notes.optString("agent_id",    "");
        String noteResource = notes.optString("resource_id", "");

        if (!agentId.equals(noteAgent)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "agent_id mismatch in order notes: " + noteAgent, orderId, paymentId);
            return err(403, "This order was not issued to agent: " + agentId);
        }
        if (!resourceId.equals(noteResource)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "resource_id mismatch in order notes: " + noteResource, orderId, paymentId);
            return err(403, "This order was not issued for resource: " + resourceId);
        }

        // Step 4: Payment must be captured.
        String status = (String) payment.get("status");
        if (!"captured".equals(status)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "Not captured – status: " + status, orderId, paymentId);
            return err(402, "Payment not captured. Status: " + status);
        }

        int capturedPaise = (int) payment.get("amount");
        if (capturedPaise != orderAmount) {
            AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_DENIED,
                    "Amount mismatch: captured=" + capturedPaise + " order=" + orderAmount, orderId, paymentId);
            return err(400, "Captured payment amount (" + capturedPaise + " paise) does not match order amount (" + orderAmount + " paise).");
        }

        // Step 5: Atomic consume + commit inside one BEGIN IMMEDIATE transaction.
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            }
            try {
                boolean consumed = AgentDatabase.atomicConsumeChallenge(conn, orderId);
                if (!consumed) {
                    conn.rollback();
                    // Challenge missing or already consumed — replay or stale request.
                    AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DUPLICATE,
                            "Challenge not PENDING for order: " + orderId, orderId, paymentId);
                    return err(409, "Order already consumed or not found. Replay rejected.");
                }

                // Move reserved → spent via canonical PolicyEngine method.
                AgentPolicyEngine.commitReservation(conn, agentId, capturedPaise);

                conn.commit();

            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_ERROR,
                        "Atomic commit failed: " + e.getMessage(), orderId, paymentId);
                return err(500, "Accounting error: " + e.getMessage());
            }
        } catch (SQLException e) {
            return err(500, "DB connection error: " + e.getMessage());
        }

        // Step 6: Durable VERIFIED record (replay protection index fires here if duplicate).
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

    // ----- misc helpers ----------------------------------------------------

    // Receipt must be <= 40 chars for Razorpay Orders API.
    private String receipt(String prefix, String resourceId) {
        long ts = System.currentTimeMillis() % 1_000_000_000L;
        String base = prefix + (resourceId.length() > 20 ? resourceId.substring(0, 20) : resourceId);
        return base + "_" + ts;
    }

    private JSONObject extractNotes(Order order) {
        try {
            Object raw = order.get("notes");
            if (raw instanceof JSONObject) return (JSONObject) raw;
            return new JSONObject(raw.toString());
        } catch (Exception e) { return new JSONObject(); }
    }

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
