package com.onepipe.service;

import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/** Pure calculations. Domain amounts are NGN; only toKobo produces provider units. */
@Component
public class PaymentCalculator {
    // Bounds the size of a schedule returned by this prototype's API, not a provider limit.
    public static final int MAX_INSTALLMENTS = 10_000;

    public BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || (long) amount.precision() - amount.scale() > 36) {
            throw new InvalidPaymentException("Amount must be positive NGN with at most two decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    public BigDecimal toKobo(BigDecimal naira) {
        return money(naira).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
    }

    public void validatePlan(PaymentType type, InstallmentFrequency frequency, Integer count) {
        if (type == null) throw new InvalidPaymentException("Payment type is required");
        if (type != PaymentType.SINGLE_PAYMENT && frequency == null) {
            throw new InvalidPaymentException("Recurring payments require a frequency");
        }
        if (type == PaymentType.INSTALLMENT && (count == null || count <= 0 || count > MAX_INSTALLMENTS)) {
            throw new InvalidPaymentException("Installment count must be between 1 and " + MAX_INSTALLMENTS);
        }
    }

    public InstallmentPlan installments(BigDecimal amount, Integer count) {
        BigDecimal total = money(amount);
        if (count == null || count <= 0 || count > MAX_INSTALLMENTS) {
            throw new InvalidPaymentException("Invalid installment count");
        }
        BigDecimal down = total.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
        return installments(total, down, count);
    }

    /** Uses the invoice snapshot, so later fee changes cannot change an existing schedule. */
    public InstallmentPlan installments(BigDecimal amount, BigDecimal plannedDown, Integer count) {
        BigDecimal total = money(amount);
        BigDecimal down = money(plannedDown);
        if (count == null || count <= 0 || count > MAX_INSTALLMENTS || down.compareTo(total) >= 0) {
            throw new InvalidPaymentException("Invalid installment configuration");
        }
        BigInteger remainingKobo = total.subtract(down).movePointRight(2).toBigIntegerExact();
        BigInteger[] split = remainingKobo.divideAndRemainder(BigInteger.valueOf(count));
        if (split[0].signum() <= 0) {
            throw new InvalidPaymentException("Every installment must be at least NGN 0.01");
        }
        int extraKobo = split[1].intValueExact();
        List<BigDecimal> amounts = IntStream.range(0, count)
                .mapToObj(i -> new BigDecimal(split[0].add(i < extraKobo ? BigInteger.ONE : BigInteger.ZERO), 2))
                .toList();
        return new InstallmentPlan(total, down, amounts);
    }

    public LocalDateTime nextBillingDate(LocalDateTime created, InstallmentFrequency frequency) {
        if (created == null || frequency == null) throw new InvalidPaymentException("Billing date and frequency are required");
        return switch (frequency) {
            case DAILY -> created.plusDays(1);
            case WEEKLY -> created.plusWeeks(1);
            case MONTHLY -> created.plusMonths(1);
        };
    }

    public record InstallmentPlan(BigDecimal total, BigDecimal downPayment, List<BigDecimal> installments) { }
}
