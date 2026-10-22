package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.*;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class PaymentCalculatorTest {
    private final PaymentCalculator calculator = new PaymentCalculator();

    @Test
    void requestedThreeInstallmentExampleIsExact() {
        var plan = calculator.installments(new BigDecimal("100000.00"), 3);
        assertThat(plan.downPayment()).isEqualByComparingTo("20000.00");
        assertThat(plan.installments()).containsExactly(new BigDecimal("26666.67"),
                new BigDecimal("26666.67"), new BigDecimal("26666.66"));
        assertThat(plan.installments().stream().reduce(plan.downPayment(), BigDecimal::add)).isEqualTo(plan.total());
    }

    @ParameterizedTest
    @CsvSource({"100000.00,1", "100000.00,2", "100000.00,3", "100000.00,7", "100000.00,12",
            "100000.00,10000", "123.47,7", "0.03,2", "0.10,8", "1.03,19", "999999.99,31"})
    void schedulesHavePositiveTwoDecimalAmountsAndExactTotals(String amount, int count) {
        var plan = calculator.installments(new BigDecimal(amount), count);
        assertThat(plan.installments()).hasSize(count).allSatisfy(value -> {
            assertThat(value).isPositive(); assertThat(value.scale()).isEqualTo(2);
        });
        assertThat(plan.installments().stream().reduce(plan.downPayment(), BigDecimal::add)).isEqualTo(plan.total());
        BigDecimal max = plan.installments().stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = plan.installments().stream().min(BigDecimal::compareTo).orElseThrow();
        assertThat(max.subtract(min)).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void downPaymentUsesExplicitHalfUpRounding() {
        assertThat(calculator.installments(new BigDecimal("100.03"), 3).downPayment()).isEqualTo(new BigDecimal("20.01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "1.001", "100.000", "9999999999999999999999999999999999999", "1E+2147483647"})
    void rejectsInvalidDomainAmounts(String amount) {
        assertThatThrownBy(() -> calculator.money(new BigDecimal(amount))).isInstanceOf(InvalidPaymentException.class);
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> calculator.money(null)).isInstanceOf(InvalidPaymentException.class);
    }

    @ParameterizedTest
    @NullSource @ValueSource(ints = {0, -1, 10001})
    void rejectsInvalidCountsWithoutDefaults(Integer count) {
        assertThatThrownBy(() -> calculator.installments(new BigDecimal("100000.00"), count))
                .isInstanceOf(InvalidPaymentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "0.02", "0.03"})
    void rejectsSchedulesThatWouldHaveZeroCollections(String amount) {
        assertThatThrownBy(() -> calculator.installments(new BigDecimal(amount), 3)).isInstanceOf(InvalidPaymentException.class);
    }

    @ParameterizedTest
    @CsvSource({"100000.00,10000000", "275.25,27525", "0.01,1", "123,12300"})
    void convertsNairaToIntegerKoboExactly(String naira, String kobo) {
        assertThat(calculator.toKobo(new BigDecimal(naira))).isEqualTo(new BigDecimal(kobo));
    }

    @ParameterizedTest
    @EnumSource(InstallmentFrequency.class)
    void datesRespectEverySupportedIntervalIncludingCalendarMonthEnd(InstallmentFrequency frequency) {
        var start = LocalDateTime.of(2024, 1, 31, 12, 0);
        var expected = switch (frequency) {
            case DAILY -> LocalDateTime.of(2024, 2, 1, 12, 0);
            case WEEKLY -> LocalDateTime.of(2024, 2, 7, 12, 0);
            case MONTHLY -> LocalDateTime.of(2024, 2, 29, 12, 0);
        };
        assertThat(calculator.nextBillingDate(start, frequency)).isEqualTo(expected);
    }
}
