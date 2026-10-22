package com.onepipe.service;

import com.onepipe.config.MockPaymentSimulationCondition;
import com.onepipe.dtos.response.PaymentCollectionResult;
import com.onepipe.security.ResourceAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@Conditional(MockPaymentSimulationCondition.class)
@RequiredArgsConstructor
public class MockPaymentSimulationService {
    private final ResourceAuthorization authorization;
    private final PaymentEventProcessor events;
    private final Environment environment;

    public PaymentCollectionResult simulate(String transactionRef, String slot) {
        authorization.requireSuperAdmin();
        if (!MockPaymentSimulationCondition.enabled(environment)) {
            throw new AccessDeniedException("Payment simulation is unavailable");
        }
        return events.simulate(transactionRef, slot);
    }
}
