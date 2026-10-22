package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.PaymentCollection;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.PaymentCollectionRepository;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Service @RequiredArgsConstructor
public class PaymentAccounting {
    private final PaymentCollectionRepository collections;
    private final PaymentCalculator calculator;

    @Transactional(readOnly = true)
    public Progress snapshot(Payment payment) {
        boolean subscription = payment.getPaymentType() == PaymentType.SUBSCRIPTION;
        if (!Integer.valueOf(1).equals(payment.getAccountingVersion())) {
            return new Progress(null, null, null, subscription ? "UNBOUNDED_SUBSCRIPTION" : "UNKNOWN");
        }
        List<PaymentCollection> entries = payment.getId() == null ? List.of() : collections.findByPaymentId(payment.getId());
        BigDecimal total = calculator.money(payment.getTotalAmount());
        BigDecimal confirmed = entries.stream().map(PaymentCollection::getConfirmedAmount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        int completed = (int) entries.stream().filter(c -> c.getCollectionType() != CollectionType.DOWN_PAYMENT).count();
        // A marker does not turn unsupported old/manual state changes into ledger evidence.
        if (entries.isEmpty() && ((payment.getStatus() == PaymentStatus.ACTIVE || payment.getStatus() == PaymentStatus.SUCCESSFUL)
                || (payment.getCompletedPayments() != null && payment.getCompletedPayments() != 0))) {
            return new Progress(null, null, null, subscription ? "UNBOUNDED_SUBSCRIPTION" : "UNKNOWN");
        }
        if (!subscription && confirmed.compareTo(total) > 0) {
            throw new ResourceConflictException("Collection ledger exceeds the finite obligation");
        }
        return new Progress(confirmed, subscription ? null : total.subtract(confirmed), completed,
                subscription ? "UNBOUNDED_SUBSCRIPTION" : entries.isEmpty() ? "UNPAID" : "CONFIRMED_COLLECTIONS");
    }

    public record Progress(BigDecimal confirmedAmount, BigDecimal remainingAmount,
                           Integer completedPayments, String balanceBasis) { }
}
