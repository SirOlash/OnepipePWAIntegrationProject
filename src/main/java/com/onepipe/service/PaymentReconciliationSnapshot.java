package com.onepipe.service;

import com.onepipe.security.ResourceAuthorization;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

/** A coherent local snapshot, fully materialized before consulting process-local adapter metadata. */
@Service
@RequiredArgsConstructor
public class PaymentReconciliationSnapshot {
    private final ResourceAuthorization authorization;
    private final PaymentAccounting accounting;
    private final PaymentSchedule schedule;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Snapshot readAuthorized(String onePipeId) {
        var payment = authorization.payment(onePipeId);
        var progress = accounting.snapshot(payment);
        return new Snapshot(payment.getStatus() == null ? "UNKNOWN" : payment.getStatus().name(), payment.getRequestRef(), payment.getTransactionRef(),
                payment.getOnePipePaymentId(), progress.confirmedAmount(), progress.remainingAmount(),
                progress.balanceBasis(), schedule.invoiceWindowStatus(payment), stateConsistent(payment, progress));
    }

    private Boolean stateConsistent(Payment payment, PaymentAccounting.Progress progress) {
        if (progress.confirmedAmount() == null || progress.completedPayments() == null) return null;
        if (payment.getStatus() == null || payment.getPaymentType() == null
                || !Objects.equals(payment.getCompletedPayments(), progress.completedPayments())) return false;
        boolean subscription = payment.getPaymentType() == PaymentType.SUBSCRIPTION;
        int confirmed = progress.confirmedAmount().signum();
        return switch (payment.getStatus()) {
            case PENDING, FAILED -> confirmed == 0;
            case ACTIVE -> payment.getPaymentType() != PaymentType.SINGLE_PAYMENT && confirmed > 0
                    && (subscription || progress.remainingAmount().signum() > 0);
            case SUCCESSFUL -> !subscription && progress.remainingAmount().signum() == 0;
            case CANCELLED -> subscription;
        };
    }

    public record Snapshot(String status, String requestRef, String transactionRef, String onePipePaymentId,
                           BigDecimal confirmedAmount, BigDecimal remainingAmount, String balanceBasis,
                           String invoiceWindowStatus, Boolean localStateConsistent) { }
}
