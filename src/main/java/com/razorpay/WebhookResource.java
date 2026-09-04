package com.razorpay;

import org.json.JSONObject;

import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/*
 * Receives Razorpay webhook events.
 *
 * Decision isolation: webhooks write DECISION_WEBHOOK — NOT DECISION_VERIFIED.
 * isAlreadyCredited() only queries VERIFIED rows via the partial unique index,
 * so webhook delivery never causes a spurious 409 on the agent retry path.
 *
 * Idempotency: duplicate payment.captured deliveries are deduplicated via
 * the payment_event table (UNIQUE on event_type + payment_id). Razorpay may
 * deliver the same event more than once; this handler is safe to receive it
 * multiple times.
 *
 * Setup:
 *   Set RAZORPAY_WEBHOOK_SECRET env var and register /webhook/razorpay
 *   in the Razorpay dashboard. Handled event: payment.captured.
 */
@Path("/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookResource {

    private final String webhookSecret;

    public WebhookResource(String secretKey, String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @POST
    @Path("/razorpay")
    public Response handle(@HeaderParam("X-Razorpay-Signature") String sig, String body) {
        if (webhookSecret == null || webhookSecret.isEmpty())
            return err(503, "Webhook secret not configured.");
        if (sig == null || sig.isEmpty())
            return err(400, "Missing X-Razorpay-Signature.");

        boolean valid;
        try { valid = Utils.verifyWebhookSignature(body, sig, webhookSecret); }
        catch (RazorpayException e) { return err(400, "Signature error: " + e.getMessage()); }
        if (!valid) return err(401, "Webhook signature invalid.");

        JSONObject event;
        try { event = new JSONObject(body); }
        catch (Exception e) { return err(400, "Body is not valid JSON."); }

        String type = event.optString("event", "");
        if ("payment.captured".equals(type)) return onPaymentCaptured(event);

        return Response.ok(new JSONObject()
            .put("status", "ignored").put("event", type).toString()).build();
    }

    private Response onPaymentCaptured(JSONObject event) {
        String paymentId, orderId;
        long   amount;
        try {
            JSONObject entity = event.getJSONObject("payload")
                                     .getJSONObject("payment")
                                     .getJSONObject("entity");
            paymentId = entity.getString("id");
            orderId   = entity.optString("order_id", null);
            amount    = entity.optLong("amount", 0);
        } catch (Exception e) {
            return err(400, "Malformed payload: " + e.getMessage());
        }

        // Deduplicate: returns false if this (event_type, payment_id) was already seen.
        boolean isNew = AgentDatabase.recordWebhookEvent("payment.captured", paymentId, orderId);
        if (!isNew) {
            return Response.ok(new JSONObject()
                .put("status",     "duplicate")
                .put("payment_id", paymentId)
                .put("message",    "Event already processed.")
                .toString()).build();
        }

        // Log the event — DECISION_WEBHOOK keeps this row out of the replay-protection index.
        AuditLog.record("webhook", null, amount, AuditLog.DECISION_WEBHOOK,
                "payment.captured received", orderId, null);

        return Response.ok(new JSONObject()
            .put("status",       "acknowledged")
            .put("payment_id",   paymentId)
            .put("amount_paise", amount)
            .toString()).build();
    }

    private Response err(int status, String msg) {
        return Response.status(status)
            .entity(new JSONObject().put("error", true).put("message", msg).toString())
            .build();
    }
}
