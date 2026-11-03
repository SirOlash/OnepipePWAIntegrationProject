package com.onepipe.service;

import com.onepipe.security.ResourceAuthorization;
import com.onepipe.config.MockPaymentSimulationCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class PaymentCollectionGuard {
    private final Environment environment;
    private final ResourceAuthorization authorization;

    public void requireSimulation() {
        if (!MockPaymentSimulationCondition.enabled(environment)) {
            throw new AccessDeniedException("Payment simulation is unavailable");
        }
        authorization.requireSuperAdmin();
    }
}
