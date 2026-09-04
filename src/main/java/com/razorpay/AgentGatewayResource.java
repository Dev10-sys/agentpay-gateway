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

/*
 * x402-style machine-payment gateway.
 *
 * GET /agent/resource/{id}  — no proof headers  → 402 + Razorpay order
 * GET /agent/resource/{id}  — with proof headers → verify + 200 or error
 *
 * Proof headers: X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature
 *
 * POST /agent/resource/{id}/verify — same flow but accepts a JSON body
 *                                    (handy for curl / Postman testing)
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
public class AgentGatewayResource {

    private static final long RESOURCE_PRICE_PAISE = 100L;

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

        if (agentId == null || agentId.trim().isEmpty()) agentId = "anonymous-agent";

        boolean hasProof = isNotBlank(paymentId) && isNotBlank(orderId) && isNotBlank(signature);
        return hasProof
            ? verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature)
            : issueChallenge(agentId, resourceId);
    }

    @POST
    @Path("/resource/{resourceId}/verify")
    public Response verifyDebug(@PathParam("resourceId") String resourceId, String body) {
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception e) {
            return error(400, "Invalid JSON.");
        }

        String agentId   = json.optString("agent_id",             "debug-agent");
        String paymentId = json.optString("razorpay_payment_id",  "");
        String orderId   = json.optString("razorpay_order_id",    "");
        String signature = json.optString("razorpay_signature",   "");

        if (!isNotBlank(paymentId) || !isNotBlank(orderId) || !isNotBlank(signature)) {
            return error(400, "razorpay_payment_id, razorpay_order_id and razorpay_signature are required.");
        }

        return verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature);
    }

    // ----- private ---------------------------------------------------------

    private Response issueChallenge(String agentId, String resourceId) {
        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, 0);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED, policy.reason, null, null);
            return error(403, "Agent denied: " + policy.reason);
        }

        try {
            JSONObject opts = new JSONObject()
                .put("amount",          RESOURCE_PRICE_PAISE)
                .put("currency",        "INR")
                .put("receipt",         "agentpay_" + resourceId + "_" + System.currentTimeMillis())
                .put("payment_capture", 1)
                .put("notes", new JSONObject()
                    .put("agent_id",    agentId)
                    .put("resource_id", resourceId)
                    .put("gateway",     "agentpay-x402"));

            Order order           = client.Orders.create(opts);
            String razorpayOrderId = (String) order.get("id");
            int    amountPaise     = (int)    order.get("amount");

            AuditLog.record(agentId, resourceId, amountPaise,
                    AuditLog.DECISION_APPROVED, "402 issued – " + razorpayOrderId, razorpayOrderId, null);

            return Response.status(402).entity(new JSONObject()
                .put("x402_version",      "1.0-INR")
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

        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_ERROR,
                    "Order create failed: " + e.getMessage(), null, null);
            return error(502, "Razorpay order creation failed: " + e.getMessage());
        }
    }

    private Response verifyAndUnlock(String agentId, String resourceId,
                                     String paymentId, String orderId, String signature) {

        // Replay guard — same payment can't unlock twice.
        if (AuditLog.isAlreadyCredited(paymentId)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DUPLICATE,
                    "Replay rejected", orderId, paymentId);
            return error(409, "Payment " + paymentId + " has already been credited. Replay rejected.");
        }

        // HMAC-SHA256 signature check.
        boolean sigValid;
        try {
            sigValid = Utils.verifyPaymentSignature(new JSONObject()
                .put("razorpay_payment_id", paymentId)
                .put("razorpay_order_id",   orderId)
                .put("razorpay_signature",  signature), this.secretKey);
        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_ERROR,
                    "Sig verify threw: " + e.getMessage(), orderId, paymentId);
            return error(400, "Signature verification failed: " + e.getMessage());
        }

        if (!sigValid) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "HMAC mismatch", orderId, paymentId);
            return error(401, "Payment signature is invalid.");
        }

        // Confirm payment is captured on Razorpay's end.
        Payment payment;
        try {
            payment = client.Payments.fetch(paymentId);
        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_ERROR,
                    "Payments.fetch failed: " + e.getMessage(), orderId, paymentId);
            return error(502, "Could not fetch payment: " + e.getMessage());
        }

        String status = (String) payment.get("status");
        if (!"captured".equals(status)) {
            AuditLog.record(agentId, resourceId, 0, AuditLog.DECISION_DENIED,
                    "Not captured – status: " + status, orderId, paymentId);
            return error(402, "Payment not captured. Status: " + status);
        }

        int capturedPaise = (int) payment.get("amount");

        AgentPolicyEngine.PolicyResult policy = AgentPolicyEngine.checkAndDebit(agentId, capturedPaise);
        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_DENIED,
                    "Policy blocked: " + policy.reason, orderId, paymentId);
            return error(403, "Policy check failed: " + policy.reason);
        }

        AuditLog.record(agentId, resourceId, capturedPaise, AuditLog.DECISION_VERIFIED,
                "Resource unlocked", orderId, paymentId);

        return Response.ok(new JSONObject()
            .put("status",       "ok")
            .put("resource_id",  resourceId)
            .put("resource",     buildResource(resourceId))
            .put("payment_id",   paymentId)
            .put("amount_paise", capturedPaise)
            .put("message",      "Access granted via AgentPay/x402-INR.")
            .toString()).build();
    }

    private String buildResource(String resourceId) {
        return new JSONObject()
            .put("resource_id", resourceId)
            .put("content",     "Protected content for: " + resourceId)
            .put("data_points", new org.json.JSONArray()
                .put("INR settlement: \u20b9" + (RESOURCE_PRICE_PAISE / 100) + ".00")
                .put("Protocol: AgentPay/x402-INR")
                .put("Served at: " + java.time.Instant.now()))
            .toString();
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
