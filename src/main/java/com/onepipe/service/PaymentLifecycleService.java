package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import com.onepipe.exceptions.customExceptions.ResourceNotFoundException;
import com.onepipe.security.ResourceAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** Local lifecycle changes serialize with collection confirmations on the same payment row. */
@Service
@RequiredArgsConstructor
public class PaymentLifecycleService {
    private final PaymentRepository payments;
    private final ResourceAuthorization authorization;
    private final PaymentAccounting accounting;
    private final PaymentSchedule schedule;
    private final Environment environment;
    private final PlatformTransactionManager transactionManager;

    public void cancelByProviderId(String providerId) {
        independentTransaction().executeWithoutResult(ignored -> {
            authorization.requireUser();
            Payment payment;
            try {
                payment = payments.findLockedByOnePipePaymentId(providerId).orElseThrow(ResourceNotFoundException::new);
            } catch (IncorrectResultSizeDataAccessException ambiguous) {
                throw new AccessDeniedException("Access denied");
            }
            cancelLocked(payment);
        });
    }

    public void cancel(Long paymentId) {
        independentTransaction().executeWithoutResult(ignored -> {
            Payment payment = payments.findLockedById(paymentId).orElseThrow(ResourceNotFoundException::new);
            cancelLocked(payment);
        });
    }

    private void cancelLocked(Payment payment) {
        authorization.checkPayments(List.of(payment));
        if (!mockMode() || payment.getPaymentType() != PaymentType.SUBSCRIPTION
                || !Integer.valueOf(1).equals(payment.getAccountingVersion())) throw conflict();
        PaymentAccounting.Progress progress = accounting.snapshot(payment);
        if (progress.confirmedAmount() == null || progress.completedPayments() == null
                || !Objects.equals(payment.getCompletedPayments(), progress.completedPayments())) throw conflict();
        if (payment.getStatus() == PaymentStatus.CANCELLED) return;
        if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.ACTIVE) throw conflict();
        if ((payment.getStatus() == PaymentStatus.PENDING) != (progress.confirmedAmount().signum() == 0)) throw conflict();
        payment.setStatus(PaymentStatus.CANCELLED);
        payments.saveAndFlush(payment);
    }

    /** Each row gets its own transaction; no stale managed entity survives the scan. */
    public void expireAll() {
        if (!mockMode()) return;
        for (Long id : payments.findAllPaymentIds()) expireOne(id);
    }

    public void expireOne(Long paymentId) {
        independentTransaction().executeWithoutResult(ignored -> {
            if (!mockMode()) return;
            Payment payment = payments.findLockedById(paymentId).orElse(null);
            if (payment == null || !Integer.valueOf(1).equals(payment.getAccountingVersion())
                    || payment.getVirtualAccountExpiredAt() != null) return;
            schedule.virtualAccountExpiry(payment).filter(expiry -> !schedule.now().isBefore(expiry)).ifPresent(expiry -> {
                payment.setVirtualAccountExpiredAt(expiry);
                payments.saveAndFlush(payment);
            });
        });
    }

    private boolean mockMode() { return "mock".equalsIgnoreCase(environment.getProperty("onepipe.mode", "mock")); }

    private TransactionTemplate independentTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private ResourceConflictException conflict() {
        return new ResourceConflictException("Subscription cancellation conflicts with the supported local lifecycle");
    }
}
