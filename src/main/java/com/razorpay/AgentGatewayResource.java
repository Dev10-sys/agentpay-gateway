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
 * AgentGatewayResource — the HTTP-402 machine-payment gateway.
 *
 * Canonical flow (x402-style, single endpoint):
 *
 *   1. Agent sends GET /agent/resource/{resourceId}  (no proof headers)
 *      → Server returns 402 with JSON containing a fresh Razorpay order.
 *
 *   2. Agent pays via Razorpay Checkout (browser step / simulator).
 *
 *   3. Agent retries GET /agent/resource/{resourceId} with three headers:
 *        X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature
 *      → Server verifies signature + payment status, returns 200 with resource.
 *
 * Debug endpoint (not the canonical path):
 *   POST /agent/resource/{resourceId}/verify  — accepts JSON body for curl/Postman.
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
public class AgentGatewayResource {

    private static final long RESOURCE_PRICE_PAISE = 100L;

    private final RazorpayClient client;
    private final String secretKey;

    public AgentGatewayResource(String apiKey, String secretKey) {
        this.secretKey = secretKey;
        try {
            this.client = new RazorpayClient(apiKey, secretKey);
        } catch (RazorpayException e) {
            throw new RuntimeException("Failed to initialise RazorpayClient: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/resource/{resourceId}")
    public Response getResource(
            @PathParam("resourceId") String resourceId,
            @HeaderParam("X-Agent-Id")             String agentId,
            @HeaderParam("X-Razorpay-Payment-Id")  String paymentId,
            @HeaderParam("X-Razorpay-Order-Id")    String orderId,
            @HeaderParam("X-Razorpay-Signature")   String signature) {

        if (agentId == null || agentId.trim().isEmpty()) {
            agentId = "anonymous-agent";
        }

        boolean hasProof = isNotBlank(paymentId) && isNotBlank(orderId) && isNotBlank(signature);

        if (hasProof) {
            return verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature);
        } else {
            return issuePaymentChallenge(agentId, resourceId);
        }
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
            return error(400, "Invalid JSON body");
        }

        String agentId   = json.optString("agent_id",   "debug-agent");
        String paymentId = json.optString("razorpay_payment_id", "");
        String orderId   = json.optString("razorpay_order_id",   "");
        String signature = json.optString("razorpay_signature",  "");

        if (!isNotBlank(paymentId) || !isNotBlank(orderId) || !isNotBlank(signature)) {
            return error(400, "Missing razorpay_payment_id, razorpay_order_id or razorpay_signature");
        }

        return verifyAndUnlock(agentId, resourceId, paymentId, orderId, signature);
    }

    private Response issuePaymentChallenge(String agentId, String resourceId) {
        AgentPolicyEngine.PolicyResult policy =
                AgentPolicyEngine.checkAndDebit(agentId, 0);

        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DENIED, policy.reason, null, null);
            return Response.status(403)
                    .entity(new JSONObject()
                            .put("error", "agent_denied")
                            .put("message", policy.reason)
                            .toString())
                    .build();
        }

        try {
            JSONObject orderOptions = new JSONObject();
            orderOptions.put("amount",          RESOURCE_PRICE_PAISE);
            orderOptions.put("currency",        "INR");
            orderOptions.put("receipt",         "agentpay_" + resourceId + "_" + System.currentTimeMillis());
            orderOptions.put("payment_capture", 1);

            JSONObject notes = new JSONObject();
            notes.put("agent_id",    agentId);
            notes.put("resource_id", resourceId);
            notes.put("gateway",     "agentpay-x402");
            orderOptions.put("notes", notes);

            Order order = client.Orders.create(orderOptions);

            String razorpayOrderId = (String) order.get("id");
            int    amountPaise     = (int)    order.get("amount");

            AuditLog.record(agentId, resourceId, amountPaise,
                    AuditLog.DECISION_APPROVED,
                    "402 challenge issued – order created",
                    razorpayOrderId, null);

            JSONObject resp = new JSONObject();
            resp.put("x402_version",    "1.0-INR");
            resp.put("status",          "payment_required");
            resp.put("resource_id",     resourceId);
            resp.put("amount_paise",    amountPaise);
            resp.put("currency",        "INR");
            resp.put("razorpay_order_id", razorpayOrderId);
            resp.put("description",     "Pay to unlock resource: " + resourceId);
            resp.put("instructions",    "Attach X-Razorpay-Payment-Id, X-Razorpay-Order-Id, " +
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

    private Response verifyAndUnlock(String agentId, String resourceId,
                                     String paymentId, String orderId, String signature) {

        if (AuditLog.isAlreadyCredited(paymentId)) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DUPLICATE,
                    "Payment already credited – replay rejected",
                    orderId, paymentId);
            return error(409, "Payment " + paymentId + " has already been credited. Replay rejected.");
        }

        JSONObject sigOptions = new JSONObject();
        sigOptions.put("razorpay_payment_id", paymentId);
        sigOptions.put("razorpay_order_id",   orderId);
        sigOptions.put("razorpay_signature",  signature);

        boolean sigValid;
        try {
            sigValid = Utils.verifyPaymentSignature(sigOptions, this.secretKey);
        } catch (RazorpayException e) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_ERROR,
                    "Signature verification threw: " + e.getMessage(),
                    orderId, paymentId);
            return error(400, "Signature verification error: " + e.getMessage());
        }

        if (!sigValid) {
            AuditLog.record(agentId, resourceId, 0,
                    AuditLog.DECISION_DENIED,
                    "HMAC signature mismatch – proof rejected",
                    orderId, paymentId);
            return error(401, "Payment signature is invalid.");
        }

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
                    "Payment status is '" + status + "', expected 'captured'",
                    orderId, paymentId);
            return error(402, "Payment not captured yet. Status: " + status);
        }

        int capturedAmount = (int) payment.get("amount");

        AgentPolicyEngine.PolicyResult policy =
                AgentPolicyEngine.checkAndDebit(agentId, capturedAmount);

        if (!policy.allowed) {
            AuditLog.record(agentId, resourceId, capturedAmount,
                    AuditLog.DECISION_DENIED,
                    "Policy blocked after payment: " + policy.reason,
                    orderId, paymentId);
            return error(403, "Payment captured but policy check failed: " + policy.reason);
        }

        AuditLog.record(agentId, resourceId, capturedAmount,
                AuditLog.DECISION_VERIFIED,
                "Payment verified and resource unlocked",
                orderId, paymentId);

        String resourceContent = buildResourcePayload(resourceId);

        JSONObject resp = new JSONObject();
        resp.put("status",          "ok");
        resp.put("resource_id",     resourceId);
        resp.put("resource",        resourceContent);
        resp.put("payment_id",      paymentId);
        resp.put("amount_paise",    capturedAmount);
        resp.put("message",         "Access granted. Welcome to the x402-style INR gateway.");

        return Response.ok(resp.toString()).build();
    }

    private String buildResourcePayload(String resourceId) {
        JSONObject payload = new JSONObject();
        payload.put("resource_id",  resourceId);
        payload.put("content",      "This is the protected content for resource: " + resourceId);
        payload.put("data_points",  new org.json.JSONArray()
                .put("INR settlement: ₹" + (RESOURCE_PRICE_PAISE / 100) + ".00")
                .put("Protocol: AgentPay/x402-INR")
                .put("Served at: " + java.time.Instant.now().toString()));
        return payload.toString();
    }

    private Response error(int status, String message) {
        JSONObject body = new JSONObject();
        body.put("error",   true);
        body.put("message", message);
        return Response.status(status).entity(body.toString()).build();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
