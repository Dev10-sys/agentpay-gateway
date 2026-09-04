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
 * Receives Razorpay webhook events (payment.captured, etc.).
 *
 * IMPORTANT — webhook vs resource path isolation (Webhook fix):
 *   This handler does NOT write DECISION_VERIFIED to audit_log.
 *   VERIFIED is reserved exclusively for the resource/metering unlock path.
 *   If webhooks wrote VERIFIED, a race between webhook delivery and the
 *   agent's retry could cause isAlreadyCredited() to fire incorrectly
 *   and block the unlock with a spurious 409.
 *
 *   Webhook events are logged as DECISION_WEBHOOK — a distinct state that
 *   isAlreadyCredited() does not query.
 *
 * Setup:
 *   Set RAZORPAY_WEBHOOK_SECRET env var and register /webhook/razorpay
 *   in the Razorpay dashboard. Handled event: payment.captured.
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

        // Log as WEBHOOK — NOT VERIFIED. The resource unlock path is the only
        // place that writes VERIFIED, so isAlreadyCredited() won't false-positive.
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
