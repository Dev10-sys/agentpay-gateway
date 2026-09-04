package com.razorpay;

import org.apache.commons.lang3.StringUtils;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.views.ViewBundle;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;

/**
 * Entry point for the AgentPay Gateway service.
 *
 * Startup order:
 *   1. Dropwizard bootstraps with env-var substitution so that server.yml
 *      placeholders (${RAZORPAY_KEY_ID}, ${RAZORPAY_SECRET}) are resolved
 *      from the OS environment — no secrets in version control.
 *   2. SQLite schema is initialised (idempotent: CREATE TABLE IF NOT EXISTS).
 *   3. Four JAX-RS resources are registered with the Jersey container.
 *
 * To start the server:
 *   java -jar target/razorpay-java-testapp-1.0-SNAPSHOT.jar server server.yml
 */
public class App extends Application<AppConfiguration> {

    @Override
    public void initialize(Bootstrap<AppConfiguration> bootstrap) {
        super.initialize(bootstrap);

        // Resolve ${ENV_VAR} placeholders in server.yml at startup.
        // strictMode=false means missing env vars are replaced with empty string
        // rather than throwing — safer for optional fields like webhookSecret.
        bootstrap.setConfigurationSourceProvider(
            new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(false)
            )
        );

        bootstrap.addBundle(new ViewBundle());
    }

    @Override
    public void run(AppConfiguration configuration, Environment environment) throws Exception {
        String apiKey        = configuration.getApiKey();
        String secretKey     = configuration.getSecretKey();
        String webhookSecret = configuration.getWebhookSecret();

        if (StringUtils.isBlank(apiKey) || StringUtils.isBlank(secretKey)) {
            throw new IllegalStateException(
                "RAZORPAY_KEY_ID and RAZORPAY_SECRET must be set as environment variables."
            );
        }

        System.out.println("[AgentPay] Starting with key: " + apiKey);

        // Initialise SQLite schema (tables + unique index for replay protection).
        AgentDatabase.initialize();

        // Register all JAX-RS resources.
        environment.jersey().register(new PaymentResource(apiKey, secretKey));
        environment.jersey().register(new AgentGatewayResource(apiKey, secretKey));
        environment.jersey().register(new UsageMeterResource(apiKey, secretKey));
        environment.jersey().register(new WebhookResource(secretKey, webhookSecret));
    }

    public static void main(String[] args) throws Exception {
        new App().run(args);
    }
}
