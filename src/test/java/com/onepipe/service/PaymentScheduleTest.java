package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

/** Calendar and boundary rules use explicit clocks, independently of machine time/zone. */
class PaymentScheduleTest {
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @ParameterizedTest
    @CsvSource({
            "DAILY,1,2025-02-01T12:00:00", "DAILY,3,2025-02-03T12:00:00",
            "WEEKLY,1,2025-02-07T12:00:00", "WEEKLY,3,2025-02-21T12:00:00",
            "MONTHLY,1,2025-02-28T12:00:00", "MONTHLY,2,2025-03-31T12:00:00",
            "MONTHLY,3,2025-04-30T12:00:00", "MONTHLY,12,2026-01-31T12:00:00"})
    void datesUseTheOriginalAnchorAndDoNotDriftAfterFebruary(InstallmentFrequency frequency,
            int ordinal, LocalDateTime expected) {
        var payment = recurring(PaymentType.SUBSCRIPTION, frequency, LocalDateTime.of(2025, 1, 31, 12, 0));
        assertThat(at("2025-01-01T00:00:00Z").dueDate(payment, ordinal)).isEqualTo(expected);
    }

    @Test
    void leapYearFebruaryClipsTheDayWithoutChangingLaterMonthEndDates() {
        var schedule = at("2024-01-01T00:00:00Z");
        var payment = recurring(PaymentType.INSTALLMENT, InstallmentFrequency.MONTHLY,
                LocalDateTime.of(2024, 1, 31, 12, 0));
        assertThat(schedule.firstBillingDate(payment)).isEqualTo(LocalDateTime.of(2024, 2, 29, 12, 0));
        assertThat(schedule.dueDate(payment, 2)).isEqualTo(LocalDateTime.of(2024, 3, 31, 12, 0));
    }

    @ParameterizedTest
    @EnumSource(InstallmentFrequency.class)
    void firstBillingDateMatchesTheFirstPlannedSlot(InstallmentFrequency frequency) {
        var schedule = at("2025-01-01T00:00:00Z");
        var payment = recurring(PaymentType.SUBSCRIPTION, frequency, LocalDateTime.of(2025, 1, 31, 12, 0));
        assertThat(schedule.firstBillingDate(payment)).isEqualTo(schedule.dueDate(payment, 1));
    }

    @ParameterizedTest
    @CsvSource({"INSTALLMENT,INSTALLMENT_1", "SUBSCRIPTION,CYCLE_1"})
    void scheduledSlotsAreRejectedJustBeforeAndAcceptedExactlyAtAndAfterTheDueDate(PaymentType type, String slot) {
        var payment = recurring(type, InstallmentFrequency.MONTHLY, LocalDateTime.of(2025, 1, 31, 12, 0));
        // 12:00 Africa/Lagos is 11:00 UTC; checking UTC guards against a silent machine-zone dependency.
        assertThatThrownBy(() -> at("2025-02-28T10:59:59.999999999Z").requireDue(payment, slot))
                .isInstanceOf(ResourceConflictException.class);
        assertThatCode(() -> at("2025-02-28T11:00:00Z").requireDue(payment, slot)).doesNotThrowAnyException();
        assertThatCode(() -> at("2025-02-28T11:00:00.000000001Z").requireDue(payment, slot)).doesNotThrowAnyException();
    }

    @Test
    void lateAndOutOfOrderDueInstallmentsAreEligibleWithoutChangingTheirSchedule() {
        var payment = recurring(PaymentType.INSTALLMENT, InstallmentFrequency.MONTHLY,
                LocalDateTime.of(2025, 1, 31, 12, 0));
        var schedule = at("2025-06-01T00:00:00Z");
        assertThatCode(() -> schedule.requireDue(payment, "INSTALLMENT_3")).doesNotThrowAnyException();
        assertThatCode(() -> schedule.requireDue(payment, "INSTALLMENT_1")).doesNotThrowAnyException();
        assertThat(schedule.dueDate(payment, 1)).isEqualTo(LocalDateTime.of(2025, 2, 28, 12, 0));
    }

    @Test
    void downPaymentIsImmediatelyEligibleRatherThanWaitingForTheFirstInstallment() {
        var payment = recurring(PaymentType.INSTALLMENT, InstallmentFrequency.MONTHLY,
                LocalDateTime.of(2025, 1, 31, 12, 0));
        assertThatCode(() -> at("2025-01-31T11:00:00Z").requireDue(payment, "DOWN_PAYMENT"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentType.class, names = {"INSTALLMENT", "SUBSCRIPTION"})
    void missingHistoricalAnchorDoesNotInventABillingDate(PaymentType type) {
        var payment = recurring(type, InstallmentFrequency.MONTHLY, null);
        var schedule = at("2025-01-31T11:00:00Z");
        assertThatThrownBy(() -> schedule.requireDue(payment,
                type == PaymentType.INSTALLMENT ? "INSTALLMENT_1" : "CYCLE_1"))
                .isInstanceOf(ResourceConflictException.class);
    }

    @ParameterizedTest
    @CsvSource({"2025-02-28T10:59:59.999999999Z,OPEN", "2025-02-28T11:00:00Z,EXPIRED",
            "2025-02-28T11:00:00.000000001Z,EXPIRED"})
    void singleInvoiceWindowUsesAnExclusiveExpiryBoundary(String now, String expected) {
        var payment = Payment.builder().paymentType(PaymentType.SINGLE_PAYMENT)
                .virtualAccountExpiryDate("2025-02-28 12:00:00").build();
        assertThat(at(now).invoiceWindowStatus(payment)).isEqualTo(expected);
        assertThat(at(now).virtualAccountExpiry(payment)).contains(Instant.parse("2025-02-28T11:00:00Z"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "2025-02-30 12:00:00", "2025-02-28T12:00:00", "2025-02-28 25:00:00"})
    void malformedOrMissingExpiryRemainsUnknownAndCannotAuthorizeANewSingleCollection(String expiry) {
        var payment = Payment.builder().paymentType(PaymentType.SINGLE_PAYMENT).virtualAccountExpiryDate(expiry).build();
        var schedule = at("2025-02-28T10:00:00Z");
        assertThat(schedule.virtualAccountExpiry(payment)).isEmpty();
        assertThat(schedule.invoiceWindowStatus(payment)).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> schedule.requireDue(payment, "FULL")).isInstanceOf(ResourceConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentType.class, names = {"INSTALLMENT", "SUBSCRIPTION"})
    void recurringInvoiceWindowCannotExpireTheEntirePlan(PaymentType type) {
        var payment = recurring(type, InstallmentFrequency.MONTHLY, LocalDateTime.of(2025, 1, 31, 12, 0));
        payment.setVirtualAccountExpiryDate("2025-01-31 13:00:00");
        var schedule = at("2025-06-01T00:00:00Z");
        assertThat(schedule.invoiceWindowStatus(payment)).isEqualTo("NOT_APPLICABLE");
        assertThatCode(() -> schedule.requireDue(payment,
                type == PaymentType.INSTALLMENT ? "INSTALLMENT_1" : "CYCLE_1")).doesNotThrowAnyException();
    }

    @Test
    void clockInstantAndAnchorUseDocumentedLagosCalendarInterpretation() {
        var schedule = new PaymentSchedule(Clock.fixed(Instant.parse("2025-01-31T23:30:00Z"), ZoneOffset.UTC));
        assertThat(schedule.now()).isEqualTo(Instant.parse("2025-01-31T23:30:00Z"));
        assertThat(schedule.anchorNow()).isEqualTo(LocalDateTime.of(2025, 2, 1, 0, 30));
    }

    private Payment recurring(PaymentType type, InstallmentFrequency frequency, LocalDateTime anchor) {
        return Payment.builder().paymentType(type).installmentFrequency(frequency).billingAnchor(anchor)
                .numberOfPayments(type == PaymentType.INSTALLMENT ? 3 : null).build();
    }

    private PaymentSchedule at(String instant) {
        return new PaymentSchedule(Clock.fixed(Instant.parse(instant), LAGOS));
    }
}
