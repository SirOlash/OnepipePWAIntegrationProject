package com.onepipe.service;

import com.onepipe.config.PaymentTimeConfiguration;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Local demonstration policy, not a statement of OnePipe billing/expiry semantics. */
@Component
public class PaymentSchedule {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final Clock clock;

    public PaymentSchedule(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDateTime anchorNow() {
        return LocalDateTime.ofInstant(now().truncatedTo(ChronoUnit.MICROS), PaymentTimeConfiguration.PAYMENT_ZONE);
    }

    public LocalDateTime firstBillingDate(Payment payment) {
        return dueDate(payment, 1);
    }

    public LocalDateTime dueDate(Payment payment, int ordinal) {
        if (payment.getBillingAnchor() == null || payment.getInstallmentFrequency() == null || ordinal < 1) {
            throw unknownSchedule();
        }
        try {
            // Always use the original anchor: Jan 31 -> Feb 28/29 -> Mar 31.
            return switch (payment.getInstallmentFrequency()) {
                case DAILY -> payment.getBillingAnchor().plusDays(ordinal);
                case WEEKLY -> payment.getBillingAnchor().plusWeeks(ordinal);
                case MONTHLY -> payment.getBillingAnchor().plusMonths(ordinal);
            };
        } catch (DateTimeException | ArithmeticException ex) {
            throw unknownSchedule();
        }
    }

    public Optional<Instant> virtualAccountExpiry(Payment payment) {
        String value = payment.getVirtualAccountExpiryDate();
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(value, EXPIRY)
                    .atZone(PaymentTimeConfiguration.PAYMENT_ZONE).toInstant());
        } catch (DateTimeException ex) {
            return Optional.empty();
        }
    }

    public String invoiceWindowStatus(Payment payment) {
        if (payment.getPaymentType() != PaymentType.SINGLE_PAYMENT) return "NOT_APPLICABLE";
        if (payment.getVirtualAccountExpiredAt() != null) return "EXPIRED";
        return virtualAccountExpiry(payment)
                .map(expiry -> now().isBefore(expiry) ? "OPEN" : "EXPIRED").orElse("UNKNOWN");
    }

    /** Called only for a NEW mock event, after durable replay/identity checks. */
    public void requireDue(Payment payment, String slot) {
        if (payment.getPaymentType() == PaymentType.SINGLE_PAYMENT) {
            if (!"OPEN".equals(invoiceWindowStatus(payment))) {
                throw new ResourceConflictException("Invoice payment window is expired or unavailable");
            }
            return;
        }
        // Down payment is a separate immediately eligible slot; it is not a recurring cycle.
        if (payment.getPaymentType() == PaymentType.INSTALLMENT && "DOWN_PAYMENT".equals(slot)) return;
        String prefix = payment.getPaymentType() == PaymentType.SUBSCRIPTION ? "CYCLE_" : "INSTALLMENT_";
        if (slot == null || !slot.startsWith(prefix) || !slot.substring(prefix.length()).matches("[1-9][0-9]{0,8}")) {
            throw unknownSchedule();
        }
        LocalDateTime due = dueDate(payment, Integer.parseInt(slot.substring(prefix.length())));
        if (now().isBefore(due.atZone(PaymentTimeConfiguration.PAYMENT_ZONE).toInstant())) {
            throw new ResourceConflictException("Planned collection is not yet due");
        }
    }

    private ResourceConflictException unknownSchedule() {
        return new ResourceConflictException("Reliable billing schedule is unavailable");
    }
}
