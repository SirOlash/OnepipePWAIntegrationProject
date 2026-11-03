package com.onepipe.service;

import com.onepipe.dtos.response.PaymentCollectionResult;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;

/** Retry only after the independent transaction has fully rolled back. No in-memory event store. */
@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentEventProcessor {
    private final PaymentCollectionTransaction worker;

    public PaymentCollectionResult confirmMock(String eventId, String transactionRef, String collectionSlot,
            BigDecimal amount, String currency, Instant confirmedAt) {
        try {
            return worker.confirmMock(eventId, transactionRef, collectionSlot, amount, currency, confirmedAt);
        } catch (DataIntegrityViolationException conflict) {
            // A concurrent global event ID on a different payment can race despite per-payment locks.
            // A fresh transaction sees the winner and classifies replay vs conflict safely.
            try {
                return worker.confirmMock(eventId, transactionRef, collectionSlot, amount, currency, confirmedAt);
            } catch (DataIntegrityViolationException repeated) {
                throw new ResourceConflictException("Collection conflicts with recorded payment state or identity");
            }
        }
    }

    public PaymentCollectionResult simulate(String transactionRef, String slot) {
        try {
            return worker.simulate(transactionRef, slot);
        } catch (DataIntegrityViolationException conflict) {
            throw new ResourceConflictException("Collection conflicts with recorded payment state or identity");
        }
    }
}
