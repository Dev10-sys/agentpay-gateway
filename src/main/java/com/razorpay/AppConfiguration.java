package com.razorpay;

import io.dropwizard.Configuration;

public class AppConfiguration extends Configuration {

    private String apiKey;
    private String secretKey;
    private String webhookSecret;
    private String dbPath = "agentpay.db";

    public String getApiKey()    { return apiKey; }
    public String getSecretKey() { return secretKey; }
    public String getDbPath()    { return dbPath; }

    public String getWebhookSecret() {
        return webhookSecret != null ? webhookSecret : "";
    }
}
