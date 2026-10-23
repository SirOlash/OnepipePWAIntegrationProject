package com.onepipe.service;

import com.onepipe.data.entities.*;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.dtos.response.PaymentCollectionResult;
import com.onepipe.exceptions.customExceptions.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One independent transaction per confirmation, including the row lock and ledger insert. */
@Service @RequiredArgsConstructor
public class PaymentCollectionTransaction {
    private final PaymentRepository payments;
    private final PaymentCollectionRepository collections;
    private final PaymentCalculator calculator;
    private final PaymentAccounting accounting;
    private final PaymentCollectionGuard guard;
    private final PaymentSchedule schedule;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentCollectionResult confirmMock(String eventId, String transactionRef, String slot,
            BigDecimal amount, String currency, Instant confirmedAt) {
        guard.requireSimulation();
        requireIdentity(eventId); requireIdentity(transactionRef);
        if (confirmedAt == null) throw invalid();
        BigDecimal normalized = calculator.money(amount);
        if (!"NGN".equals(currency)) throw invalid();
        Payment payment = payments.findLockedByTransactionRef(transactionRef).orElseThrow(ResourceNotFoundException::new);
        return confirmLocked(payment, eventId, slot, normalized, confirmedAt.truncatedTo(ChronoUnit.MICROS));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentCollectionResult simulate(String transactionRef, String slot) {
        guard.requireSimulation(); requireIdentity(transactionRef);
        Payment payment = payments.findLockedByTransactionRef(transactionRef).orElseThrow(ResourceNotFoundException::new);
        PlannedCollection expected = expected(payment, slot);
        return confirmLocked(payment, "MOCK-EVENT-" + UUID.randomUUID(), slot, expected.amount(),
                schedule.now().truncatedTo(ChronoUnit.MICROS));
    }

    private PaymentCollectionResult confirmLocked(Payment payment, String eventId, String slot,
            BigDecimal amount, Instant confirmedAt) {
        PlannedCollection expected = expected(payment, slot);
        Optional<PaymentCollection> prior = collections.findByEventId(eventId);
        if (prior.isPresent()) {
            PaymentCollection existing = prior.get();
            if (!existing.getPayment().getId().equals(payment.getId())
                    || !Objects.equals(existing.getTransactionRef(), payment.getTransactionRef())
                    || !Objects.equals(existing.getCollectionSlot(), slot)
                    || existing.getConfirmedAmount().compareTo(amount) != 0
                    || !"NGN".equals(existing.getCurrency()) || existing.getSource() != CollectionSource.MOCK
                    || existing.getCollectionType() != expected.type()
                    || !existing.getConfirmedAt().equals(confirmedAt)) {
                throw conflict();
            }
            return result(payment, existing, true);
        }
        if (amount.compareTo(expected.amount()) != 0) throw invalid();
        if (!Integer.valueOf(1).equals(payment.getAccountingVersion())) throw conflict();
        if (collections.findByPaymentIdAndCollectionSlot(payment.getId(), slot).isPresent()) throw conflict();
        if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.ACTIVE) throw conflict();
        PaymentAccounting.Progress before = accounting.snapshot(payment);
        if (before.confirmedAmount() == null || before.completedPayments() == null
                || !Objects.equals(payment.getCompletedPayments(), before.completedPayments())) throw conflict();
        if ((payment.getStatus() == PaymentStatus.PENDING) != (before.confirmedAmount().signum() == 0)) throw conflict();
        if (payment.getPaymentType() == PaymentType.SUBSCRIPTION) {
            if (!slot.equals("CYCLE_" + ((long) before.completedPayments() + 1))) throw conflict();
        } else if (before.confirmedAmount().add(amount).compareTo(payment.getTotalAmount()) > 0) {
            throw conflict();
        }
        schedule.requireDue(payment, slot);
        PaymentCollection inserted = collections.saveAndFlush(PaymentCollection.builder()
                .eventId(eventId).payment(payment).transactionRef(payment.getTransactionRef()).collectionSlot(slot)
                .confirmedAmount(amount).currency("NGN").source(CollectionSource.MOCK).confirmedAt(confirmedAt)
                .collectionType(expected.type()).build());
        PaymentAccounting.Progress after = accounting.snapshot(payment);
        payment.setCompletedPayments(after.completedPayments());
        payment.setStatus(payment.getPaymentType() != PaymentType.SUBSCRIPTION
                && after.remainingAmount().signum() == 0 ? PaymentStatus.SUCCESSFUL : PaymentStatus.ACTIVE);
        payments.saveAndFlush(payment);
        return result(payment, inserted, false);
    }

    private PlannedCollection expected(Payment payment, String slot) {
        if (slot == null || slot.length() > 50 || payment.getPaymentType() == null) throw invalid();
        BigDecimal total = calculator.money(payment.getTotalAmount());
        return switch (payment.getPaymentType()) {
            case SINGLE_PAYMENT -> {
                if (!"FULL".equals(slot)) throw invalid();
                yield new PlannedCollection(total, CollectionType.FULL);
            }
            case INSTALLMENT -> {
                PaymentCalculator.InstallmentPlan plan = calculator.installments(total,
                        payment.getDownPaymentAmount(), payment.getNumberOfPayments());
                if ("DOWN_PAYMENT".equals(slot)) yield new PlannedCollection(plan.downPayment(), CollectionType.DOWN_PAYMENT);
                int index = positiveIndex(slot, "INSTALLMENT_");
                if (index > plan.installments().size()) throw invalid();
                yield new PlannedCollection(plan.installments().get(index - 1), CollectionType.INSTALLMENT);
            }
            case SUBSCRIPTION -> {
                positiveIndex(slot, "CYCLE_");
                BigDecimal cycle = calculator.money(payment.getAmountPerCycle());
                if (cycle.compareTo(total) != 0) throw invalid();
                yield new PlannedCollection(cycle, CollectionType.SUBSCRIPTION_CYCLE);
            }
        };
    }

    private int positiveIndex(String slot, String prefix) {
        if (!slot.startsWith(prefix) || !slot.substring(prefix.length()).matches("[1-9][0-9]{0,8}")) throw invalid();
        return Integer.parseInt(slot.substring(prefix.length()));
    }

    private PaymentCollectionResult result(Payment payment, PaymentCollection collection, boolean duplicate) {
        var progress = accounting.snapshot(payment);
        return new PaymentCollectionResult(collection.getEventId(), payment.getTransactionRef(), collection.getCollectionSlot(),
                collection.getConfirmedAmount(), collection.getCurrency(), collection.getSource().name(), payment.getStatus(),
                progress.remainingAmount(), progress.completedPayments() == null
                        ? payment.getCompletedPayments() : progress.completedPayments(), duplicate, true);
    }

    private void requireIdentity(String identity) {
        if (identity == null || !identity.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) throw invalid();
    }
    private InvalidPaymentException invalid() { return new InvalidPaymentException("Invalid collection details or planned amount"); }
    private ResourceConflictException conflict() { return new ResourceConflictException("Collection conflicts with recorded payment state or identity"); }
    private record PlannedCollection(BigDecimal amount, CollectionType type) { }
}
