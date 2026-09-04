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

/**
 * AgentGatewayResource — the x402-style machine-payment gateway.
 *
 * A single endpoint serves both the payment challenge and the resource
 * delivery, distinguished by the presence or absence of proof headers.
 * This mirrors the HTTP 402 "Payment Required" flow described in the
 * x402 draft spec, adapted for INR via Razorpay.
 *
 * Canonical flow:
 *
 *   Step 1 — Discovery (no proof headers)
 *     GET /agent/resource/{resourceId}
 *     → 402 Payment Required
 *       { status, razorpay_order_id, amount_paise, currency, instructions }
 *
 *   Step 2 — Payment (out of band)
 *     Agent or user completes Razorpay Checkout using the order_id from Step 1.
 *     Razorpay returns: razorpay_payment_id, razorpay_order_id, razorpay_signature.
 *
 *   Step 3 — Proof submission (all three proof headers present)
 *     GET /agent/resource/{resourceId}
 *       X-Razorpay-Payment-Id: pay_...
 *       X-Razorpay-Order-Id:   order_...
 *       X-Razorpay-Signature:  <hmac-sha256>
 *     → 200 OK  { resource, payment_id, amount_paise }
 *
 * Security guarantees:
 *   - HMAC-SHA256 signature verified via Razorpay SDK before any DB write.
 *   - Payment status fetched live from Razorpay API; must be "captured".
 *   - razorpay_payment_id uniqueness enforced in audit_log; replay → 409.
 *   - Daily spending cap checked via AgentPolicyEngine.
 *
 * Debug endpoint (not the canonical flow — for curl/Postman testing):
 *   POST /agent/resource/{resourceId}/verify
 *   Body: { razorpay_payment_id, razorpay_order_id, razorpay_signature }
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
public class AgentGatewayResource {

    // Resource price in paise (100 paise = INR 1.00).
    private static final long RESOURCE_PRICE_PAISE = 100L;

    private final RazorpayClient client;
    private final String         secretKey;

    public AgentGatewayResource(String apiKey, String secretKey) {
        this.secretKey = secretKey;
        try {
            this.client = new RazorpayClient(apiKey, secretKey);
        } catch (RazorpayException e) {
            throw new RuntimeException("Failed to initialise RazorpayClient: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Public endpoints
    // -------------------------------------------------------------------------

    @GET
    @Path("/resource/{resourceId}")
    public Response getResource(
            @PathParam("resourceId")                 String resourceId,
            @HeaderParam("X-Agent-Id")               String agentId,
            @HeaderParam("X-Razorpay-Payment-Id")    String paymentId,
            @HeaderParam("X-Razorpay-Order-Id")      String orderId,
            @HeaderParam("X-Razorpay-Signature")     String signature) {

        if (agentId == null || agentId.trim().isEmpty()) {
            agentId = "anonymous-agent";
        }

        boolean hasProof = isNotBlank(paymentId) && isNotBlank(orderId) && isNotBlank(signature);

        return hasProof
            ? verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature)
            : issuePaymentChallenge(agentId, resourceId);
    }

    @POST
    @Path("/resource/{resourceId}/verify")
    public Response verifyDebug(
            @PathParam("resourceId") String resourceId,
            String body) {

        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception e) {
            return error(400, "Invalid JSON body.");
        }

        String agentId   = json.optString("agent_id",              "debug-agent");
        String paymentId = json.optString("razorpay_payment_id",   "");
        String orderId   = json.optString("razorpay_order_id",     "");
        String signature = json.optString("razorpay_signature",    "");

        if (!isNotBlank(paymentId) || !isNotBlank(orderId) || !isNotBlank(signature)) {
            return error(400, "razorpay_payment_id, razorpay_order_id and razorpay_signature are required.");
        }

        return verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Issues a 402 challenge by creating a fresh Razorpay order and returning
     * its ID along with payment instructions.  Runs a policy check first —
     * blocked agents receive 403 without an order being created.
     */
    private Response issuePaymentChallenge(String agentId, String resourceId) {
        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, 0);

        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DENIED, policy.reason, null, null);
            return error(403, "Agent access denied: " + policy.reason);
        }

        try {
            JSONObject orderOpts = new JSONObject();
            orderOpts.put("amount",          RESOURCE_PRICE_PAISE);
            orderOpts.put("currency",        "INR");
            orderOpts.put("receipt",         "agentpay_" + resourceId + "_" + System.currentTimeMillis());
            orderOpts.put("payment_capture", 1);

            JSONObject notes = new JSONObject();
            notes.put("agent_id",    agentId);
            notes.put("resource_id", resourceId);
            notes.put("gateway",     "agentpay-x402");
            orderOpts.put("notes", notes);

            Order order = client.Orders.create(orderOpts);

            String razorpayOrderId = (String) order.get("id");
            int    amountPaise     = (int)    order.get("amount");

            AuditLog.record(agentId, resourceId, amountPaise,
                    AuditLog.DECISION_APPROVED,
                    "402 challenge issued – order " + razorpayOrderId,
                    razorpayOrderId, null);

            JSONObject resp = new JSONObject();
            resp.put("x402_version",      "1.0-INR");
            resp.put("status",            "payment_required");
            resp.put("resource_id",       resourceId);
            resp.put("amount_paise",      amountPaise);
            resp.put("currency",          "INR");
            resp.put("razorpay_order_id", razorpayOrderId);
            resp.put("description",       "Pay to unlock resource: " + resourceId);
            resp.put("instructions",
                "Attach X-Razorpay-Payment-Id, X-Razorpay-Order-Id, " +
                "X-Razorpay-Signature headers and retry this GET request.");

            return Response.status(402).entity(resp.toString()).build();

        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_ERROR,
                    "Order creation failed: " + e.getMessage(),
                    null, null);
            return error(502, "Razorpay order creation failed: " + e.getMessage());
        }
    }

    /**
     * Verifies the three proof tokens and, if valid, returns the protected
     * resource.  Verification steps (all must pass in order):
     *   1. Replay check  — payment_id must not exist in audit_log as VERIFIED.
     *   2. HMAC check    — Razorpay SDK verifies order_id + payment_id digest.
     *   3. Status check  — live Razorpay API confirms payment is "captured".
     *   4. Policy check  — daily budget has room for the captured amount.
     */
    private Response verifyAndUnlock(String agentId, String resourceId,
                                     String paymentId, String orderId, String signature) {

        // Step 1: Replay guard.
        if (AuditLog.isAlreadyCredited(paymentId)) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DUPLICATE,
                    "Replay rejected – payment already credited",
                    orderId, paymentId);
            return error(409, "Payment " + paymentId + " has already been credited. Replay rejected.");
        }

        // Step 2: HMAC-SHA256 signature verification.
        JSONObject sigOpts = new JSONObject();
        sigOpts.put("razorpay_payment_id", paymentId);
        sigOpts.put("razorpay_order_id",   orderId);
        sigOpts.put("razorpay_signature",  signature);

        boolean sigValid;
        try {
            sigValid = Utils.verifyPaymentSignature(sigOpts, this.secretKey);
        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_ERROR,
                    "Signature verification error: " + e.getMessage(),
                    orderId, paymentId);
            return error(400, "Signature verification failed: " + e.getMessage());
        }

        if (!sigValid) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DENIED,
                    "HMAC signature mismatch",
                    orderId, paymentId);
            return error(401, "Payment signature is invalid.");
        }

        // Step 3: Confirm payment is captured on Razorpay's servers.
        Payment payment;
        try {
            payment = client.Payments.fetch(paymentId);
        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_ERROR,
                    "Payments.fetch failed: " + e.getMessage(),
                    orderId, paymentId);
            return error(502, "Could not fetch payment status: " + e.getMessage());
        }

        String status = (String) payment.get("status");
        if (!"captured".equals(status)) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DENIED,
                    "Payment not captured – status: " + status,
                    orderId, paymentId);
            return error(402, "Payment not captured. Current status: " + status);
        }

        int capturedPaise = (int) payment.get("amount");

        // Step 4: Policy check and daily budget debit.
        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, capturedPaise);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, capturedPaise,
                    AuditLog.DECISION_DENIED,
                    "Policy blocked post-payment: " + policy.reason,
                    orderId, paymentId);
            return error(403, "Payment captured but policy check failed: " + policy.reason);
        }

        // All checks passed — write the VERIFIED audit record and return the resource.
        AuditLog.record(agentId, resourceId, capturedPaise,
                AuditLog.DECISION_VERIFIED,
                "Payment verified. Resource unlocked.",
                orderId, paymentId);

        JSONObject resp = new JSONObject();
        resp.put("status",       "ok");
        resp.put("resource_id",  resourceId);
        resp.put("resource",     buildResourcePayload(resourceId));
        resp.put("payment_id",   paymentId);
        resp.put("amount_paise", capturedPaise);
        resp.put("message",      "Access granted via AgentPay/x402-INR.");

        return Response.ok(resp.toString()).build();
    }

    /**
     * Builds the JSON payload for the protected resource.
     * Replace this with real data retrieval in a production deployment.
     */
    private String buildResourcePayload(String resourceId) {
        JSONObject payload = new JSONObject();
        payload.put("resource_id", resourceId);
        payload.put("content",     "Protected content for: " + resourceId);
        payload.put("data_points", new org.json.JSONArray()
            .put("INR settlement: \u20b9" + (RESOURCE_PRICE_PAISE / 100) + ".00")
            .put("Protocol: AgentPay/x402-INR")
            .put("Served at: " + java.time.Instant.now().toString()));
        return payload.toString();
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .entity(new JSONObject().put("error", true).put("message", message).toString())
                .build();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
