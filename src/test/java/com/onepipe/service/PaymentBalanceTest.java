package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.config.SchoolFeeSchedule;
import com.onepipe.security.ResourceAuthorization;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.validation.PaymentInputValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PaymentBalanceTest {
    private final PaymentService service = new PaymentService(mock(PaymentRepository.class), mock(OnepipeClient.class),
            mock(StudentRepository.class), mock(ParentRepository.class), mock(BranchRepository.class),
            mock(ResourceAuthorization.class), new PaymentCalculator(), mock(SchoolFeeSchedule.class), mock(PaymentInputValidation.class));

    @ParameterizedTest
    @EnumSource(value = PaymentType.class, names = {"SINGLE_PAYMENT", "INSTALLMENT"})
    void newUnpaidFiniteInvoicesKeepTheirFullBalance(PaymentType type) {
        Payment payment = invoice(type, PaymentStatus.PENDING, 0);
        assertThat(service.calculatePendingAmount(payment)).isEqualTo(new BigDecimal("100000.00"));
        assertThat(service.paymentTerms(payment).balanceBasis()).isEqualTo("UNPAID");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentType.class, names = {"SINGLE_PAYMENT", "INSTALLMENT"})
    void recordedSettledFiniteInvoicesHaveZeroBalance(PaymentType type) {
        Payment payment = invoice(type, PaymentStatus.SUCCESSFUL, 4);
        assertThat(service.calculatePendingAmount(payment)).isEqualTo(new BigDecimal("0.00"));
        assertThat(service.paymentTerms(payment).balanceBasis()).isEqualTo("REPORTED_SETTLED");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4, 100})
    void activeInvoiceCountersCannotProvePartialCollectionsOrProduceNegativeBalances(int count) {
        Payment payment = invoice(PaymentType.INSTALLMENT, PaymentStatus.ACTIVE, count);
        assertThat(service.calculatePendingAmount(payment)).isNull();
        assertThat(service.paymentTerms(payment).balanceBasis()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1, 3})
    void countersOnPendingInvoicesAreNotTrustedAsPaymentEvidence(int count) {
        assertThat(service.calculatePendingAmount(invoice(PaymentType.INSTALLMENT, PaymentStatus.PENDING, count))).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED"})
    void nonPendingStatesDoNotProveWhetherPriorPartialCollectionsExist(PaymentStatus status) {
        assertThat(service.calculatePendingAmount(invoice(PaymentType.INSTALLMENT, status, 0))).isNull();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void subscriptionsNeverPretendToHaveAFiniteAggregateBalance(PaymentStatus status) {
        Payment payment = invoice(PaymentType.SUBSCRIPTION, status, 0);
        assertThat(service.calculatePendingAmount(payment)).isNull();
        assertThat(service.paymentTerms(payment).balanceBasis()).isEqualTo("UNBOUNDED_SUBSCRIPTION");
    }

    private Payment invoice(PaymentType type, PaymentStatus status, int count) {
        return Payment.builder().paymentType(type).status(status).totalAmount(new BigDecimal("100000.00"))
                .downPaymentAmount(type == PaymentType.INSTALLMENT ? new BigDecimal("20000.00") : null)
                .amountPerCycle(type == PaymentType.INSTALLMENT ? new BigDecimal("26666.67") : null)
                .numberOfPayments(type == PaymentType.INSTALLMENT ? 3 : null).completedPayments(count).build();
    }
}
