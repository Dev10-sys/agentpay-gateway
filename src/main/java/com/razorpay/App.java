package com.razorpay;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.views.ViewBundle;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;

public class App extends Application<AppConfiguration> {

    @Override
    public void initialize(Bootstrap<AppConfiguration> bootstrap) {
        super.initialize(bootstrap);

        // Pick up ${RAZORPAY_KEY_ID} / ${RAZORPAY_SECRET} from env at startup.
        // strictMode=false so optional vars like WEBHOOK_SECRET don't crash us.
        bootstrap.setConfigurationSourceProvider(
            new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(false)
            )
        );

        bootstrap.addBundle(new ViewBundle());
    }

    @Override
    public void run(AppConfiguration cfg, Environment env) throws Exception {
        String apiKey        = cfg.getApiKey();
        String secretKey     = cfg.getSecretKey();
        String webhookSecret = cfg.getWebhookSecret();

        if (apiKey == null || apiKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            throw new IllegalStateException(
                "RAZORPAY_KEY_ID and RAZORPAY_SECRET env vars must be set."
            );
        }

        System.out.println("[AgentPay] key=" + apiKey);

        AgentDatabase.initialize();

        env.jersey().register(new PaymentResource(apiKey, secretKey));
        env.jersey().register(new AgentGatewayResource(apiKey, secretKey));
        env.jersey().register(new UsageMeterResource(apiKey, secretKey));
        env.jersey().register(new WebhookResource(secretKey, webhookSecret));
    }

    public static void main(String[] args) throws Exception {
        new App().run(args);
    }
}
