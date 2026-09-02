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
 * WebhookResource validates inbound Razorpay webhook events using the SDK's
 * Utils.verifyWebhookSignature and enforces idempotency before acting on them.
 *
 * Endpoint:  POST /webhook/razorpay
 * Headers:   X-Razorpay-Signature  (sent by Razorpay, required)
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
            return error(401, "Webhook signature invalid. Possible tampering.");
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

        return Response.ok(new JSONObject()
                .put("status",  "ignored")
                .put("event",   eventType)
                .put("message", "Event type not handled by AgentPay gateway.")
                .toString()).build();
    }

    private Response handlePaymentCaptured(JSONObject event) {
        String paymentId = null;
        String orderId   = null;
        long   amount    = 0;

        try {
            JSONObject payload = event.getJSONObject("payload");
            JSONObject payment = payload.getJSONObject("payment").getJSONObject("entity");
            paymentId = payment.getString("id");
            orderId   = payment.optString("order_id", null);
            amount    = payment.optLong("amount", 0);
        } catch (Exception e) {
            return error(400, "Malformed payment.captured payload: " + e.getMessage());
        }

        if (AuditLog.isAlreadyCredited(paymentId)) {
            AuditLog.record("webhook", null, amount,
                    AuditLog.DECISION_DUPLICATE,
                    "Webhook payment.captured duplicate – already credited",
                    orderId, paymentId);
            return Response.ok(new JSONObject()
                    .put("status",  "already_credited")
                    .put("message", "Idempotency: this payment_id was already processed.")
                    .toString()).build();
        }

        AuditLog.record("webhook", null, amount,
                AuditLog.DECISION_VERIFIED,
                "Webhook payment.captured processed",
                orderId, paymentId);

        return Response.ok(new JSONObject()
                .put("status",  "processed")
                .put("payment_id", paymentId)
                .put("amount_paise", amount)
                .toString()).build();
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .entity(new JSONObject().put("error", true).put("message", message).toString())
                .build();
    }
}
