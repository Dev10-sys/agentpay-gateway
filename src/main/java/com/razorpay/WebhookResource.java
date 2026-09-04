package com.razorpay;

import org.json.JSONObject;

import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * WebhookResource — receives and validates inbound Razorpay webhook events.
 *
 * Razorpay sends a POST to this endpoint for every payment lifecycle event
 * (captured, failed, refunded, etc.).  The webhook signature header is
 * verified with the Razorpay SDK before any payload is processed.
 *
 * Idempotency: events that reference a payment_id already present in
 * audit_log as VERIFIED are acknowledged but not re-processed.  This makes
 * the handler safe to receive Razorpay's duplicate delivery retries.
 *
 * Endpoint: POST /webhook/razorpay
 *
 * To enable webhooks:
 *   1. Set RAZORPAY_WEBHOOK_SECRET in the environment.
 *   2. Register https://your-host/webhook/razorpay in the Razorpay dashboard.
 *   3. Select the "payment.captured" event (minimum required by AgentPay).
 *
 * For local testing, use the Razorpay dashboard's "Test Webhook" button or
 * a tool like ngrok to expose the local server.
 */
@Path("/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookResource {

    private final String secretKey;
    private final String webhookSecret;

    public WebhookResource(String secretKey, String webhookSecret) {
        this.secretKey     = secretKey;
        this.webhookSecret = webhookSecret;
    }

    @POST
    @Path("/razorpay")
    public Response handleWebhook(
            @HeaderParam("X-Razorpay-Signature") String webhookSig,
            String body) {

        // Webhook secret is optional in server.yml; return 503 if not configured
        // rather than silently accepting unverified events.
        if (webhookSecret == null || webhookSecret.trim().isEmpty()) {
            return error(503, "Webhook secret not configured on this server.");
        }

        if (webhookSig == null || webhookSig.trim().isEmpty()) {
            return error(400, "Missing X-Razorpay-Signature header.");
        }

        boolean sigValid;
        try {
            sigValid = Utils.verifyWebhookSignature(body, webhookSig, webhookSecret);
        } catch (RazorpayException e) {
            return error(400, "Webhook signature verification error: " + e.getMessage());
        }

        if (!sigValid) {
            return error(401, "Webhook signature invalid.");
        }

        JSONObject event;
        try {
            event = new JSONObject(body);
        } catch (Exception e) {
            return error(400, "Webhook body is not valid JSON.");
        }

        String eventType = event.optString("event", "");

        if ("payment.captured".equals(eventType)) {
            return handlePaymentCaptured(event);
        }

        // Unknown event types are acknowledged with 200 but not processed.
        return Response.ok(new JSONObject()
                .put("status",  "ignored")
                .put("event",   eventType)
                .put("message", "Event type not handled by AgentPay.")
                .toString()).build();
    }

    private Response handlePaymentCaptured(JSONObject event) {
        String paymentId;
        String orderId;
        long   amount;

        try {
            JSONObject payload = event.getJSONObject("payload");
            JSONObject payment = payload.getJSONObject("payment").getJSONObject("entity");
            paymentId = payment.getString("id");
            orderId   = payment.optString("order_id", null);
            amount    = payment.optLong("amount", 0);
        } catch (Exception e) {
            return error(400, "Malformed payment.captured payload: " + e.getMessage());
        }

        // Idempotency: acknowledge without re-processing.
        if (AuditLog.isAlreadyCredited(paymentId)) {
            AuditLog.record("webhook", null, amount,
                    AuditLog.DECISION_DUPLICATE,
                    "Duplicate webhook – already credited",
                    orderId, paymentId);
            return Response.ok(new JSONObject()
                    .put("status",  "already_credited")
                    .put("message", "Idempotency: payment already processed.")
                    .toString()).build();
        }

        AuditLog.record("webhook", null, amount,
                AuditLog.DECISION_VERIFIED,
                "Webhook payment.captured processed",
                orderId, paymentId);

        return Response.ok(new JSONObject()
                .put("status",       "processed")
                .put("payment_id",   paymentId)
                .put("amount_paise", amount)
                .toString()).build();
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .entity(new JSONObject().put("error", true).put("message", message).toString())
                .build();
    }
}
