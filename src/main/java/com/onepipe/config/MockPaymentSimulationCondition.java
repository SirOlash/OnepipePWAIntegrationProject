package com.onepipe.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** The simulation HTTP surface never exists outside an explicitly enabled offline dev environment. */
public class MockPaymentSimulationCondition implements Condition {
    public static boolean enabled(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 1 && "dev".equals(profiles[0])
                && "mock".equalsIgnoreCase(environment.getProperty("onepipe.mode", "mock"))
                && "true".equalsIgnoreCase(environment.getProperty("app.demo.payments.simulation.enabled", "false"));
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return enabled(context.getEnvironment());
    }
}
