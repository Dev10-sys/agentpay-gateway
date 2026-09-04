package com.razorpay;

import io.dropwizard.Configuration;

/**
 * Dropwizard configuration bean.
 *
 * Fields are populated from server.yml at startup.  Environment-variable
 * placeholders in that file (e.g. ${RAZORPAY_KEY_ID}) are expanded by the
 * SubstitutingSourceProvider registered in App.initialize().
 */
public class AppConfiguration extends Configuration {

    private String apiKey;
    private String secretKey;
    private String webhookSecret;
    private String dbPath = "agentpay.db";

    public String getApiKey()      { return apiKey; }
    public String getSecretKey()   { return secretKey; }
    public String getDbPath()      { return dbPath; }

    // webhookSecret is optional; return empty string rather than null so callers
    // can safely use isEmpty() without a null check.
    public String getWebhookSecret() {
        return webhookSecret != null ? webhookSecret : "";
    }
}
