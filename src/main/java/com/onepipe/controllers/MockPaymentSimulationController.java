package com.onepipe.controllers;

import com.onepipe.config.MockPaymentSimulationCondition;
import com.onepipe.dtos.request.MockPaymentSimulationRequest;
import com.onepipe.dtos.response.PaymentCollectionResult;
import com.onepipe.service.MockPaymentSimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/payment-simulations")
@Conditional(MockPaymentSimulationCondition.class)
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class MockPaymentSimulationController {
    private final MockPaymentSimulationService simulation;

    @PostMapping
    public PaymentCollectionResult simulate(@Valid @RequestBody MockPaymentSimulationRequest request) {
        return simulation.simulate(request.getTransactionRef(), request.getCollectionSlot());
    }
}
