package com.onepipe.service;

import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentReconciliationServiceTest {
    private final PaymentReconciliationSnapshot snapshots = mock(PaymentReconciliationSnapshot.class);
    private final OnepipeClient provider = mock(OnepipeClient.class);
    private final PaymentReconciliationService service = new PaymentReconciliationService(snapshots, provider, "mock");

    private void snapshot(String status) {
        when(snapshots.readAuthorized("demo-id")).thenReturn(new PaymentReconciliationSnapshot.Snapshot(
                status, "demo-request", "demo-transaction", "demo-id", new BigDecimal("20000.00"),
                new BigDecimal("80000.00"), "CONFIRMED_COLLECTIONS", "EXPIRED", true));
    }

    private OnePipeResponse metadata(String status) {
        return OnePipeResponse.builder().status(status).paymentId("demo-id").transactionRef("demo-transaction")
                .message("PRIVATE diagnostic SQL and credential must never be returned").build();
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "SUCCESSFUL", "CANCELLED", "FAILED"})
    void mockPendingCannotOverwriteLocalStatusOrLedgerAmounts(String status) {
        snapshot(status); when(provider.queryTransaction("demo-transaction")).thenReturn(metadata("Pending"));
        var result = service.query("demo-id");
        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(result.getConfirmedAmount()).isEqualByComparingTo("20000.00");
        assertThat(result.getRemainingAmount()).isEqualByComparingTo("80000.00");
        assertThat(result.getBalanceBasis()).isEqualTo("CONFIRMED_COLLECTIONS");
        assertThat(result.getInvoiceWindowStatus()).isEqualTo("EXPIRED");
        assertThat(result.getProviderStateConsistent()).isFalse();
        assertThat(result.getProviderAvailability()).isEqualTo("AVAILABLE");
        assertThat(result.getMessage()).doesNotContain("PRIVATE", "credential", "SQL");
        verify(snapshots).readAuthorized("demo-id");
        verify(provider).queryTransaction("demo-transaction");
        verifyNoMoreInteractions(snapshots, provider);
    }

    @Test void oldIdentifiersAndLocalPendingStatusArePreserved() {
        snapshot("PENDING"); when(provider.queryTransaction("demo-transaction")).thenReturn(metadata("Pending"));
        var result = service.query("demo-id");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getRequestRef()).isEqualTo("demo-request");
        assertThat(result.getTransactionRef()).isEqualTo("demo-transaction");
        assertThat(result.getOnePipePaymentId()).isEqualTo("demo-id");
        assertThat(result.getProviderStateConsistent()).isTrue();
        assertThat(result.getMessage()).contains("No bank collection was verified");
    }

    @Test void lostProcessLocalInvoiceIsUnavailableRatherThanReconstructed() {
        snapshot("ACTIVE"); when(provider.queryTransaction("demo-transaction")).thenReturn(metadata("NotFound"));
        var result = service.query("demo-id");
        assertThat(result.getProviderAvailability()).isEqualTo("UNAVAILABLE");
        assertThat(result.getProviderStateConsistent()).isNull();
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(provider).queryTransaction("demo-transaction"); verifyNoMoreInteractions(provider);
    }

    @Test void adapterFailureHasSafeDiagnosticAndDoesNotDiscardLocalSnapshot() {
        snapshot("ACTIVE"); when(provider.queryTransaction("demo-transaction"))
                .thenThrow(new IllegalStateException("PRIVATE credential SQL payment identifier"));
        var result = service.query("demo-id");
        assertThat(result.getProviderAvailability()).isEqualTo("UNAVAILABLE");
        assertThat(result.getConfirmedAmount()).isEqualByComparingTo("20000.00");
        assertThat(result.getMessage()).doesNotContain("PRIVATE", "credential", "SQL", "identifier");
    }

    @Test void nullMetadataIsUnavailable() {
        snapshot("PENDING");
        assertThat(service.query("demo-id").getProviderAvailability()).isEqualTo("UNAVAILABLE");
    }

    @Test void nullStatusIsUnavailable() {
        snapshot("PENDING"); when(provider.queryTransaction("demo-transaction")).thenReturn(metadata(null));
        assertThat(service.query("demo-id").getProviderAvailability()).isEqualTo("UNAVAILABLE");
    }

    @Test void unrecognizedAdapterTextIsNotReturnedToCallers() {
        snapshot("PENDING"); when(provider.queryTransaction("demo-transaction"))
                .thenReturn(metadata("PRIVATE SQL secret response"));
        var result = service.query("demo-id");
        assertThat(result.getProviderStatus()).isEqualTo("UNKNOWN");
        assertThat(result.getProviderStateConsistent()).isFalse();
        assertThat(result.getMessage()).doesNotContain("PRIVATE", "SQL", "secret");
    }

    @Test void differentInvoiceIdentityCannotBeReportedConsistent() {
        snapshot("PENDING"); var wrong = metadata("Pending"); wrong.setPaymentId("different-demo-id");
        when(provider.queryTransaction("demo-transaction")).thenReturn(wrong);
        assertThat(service.query("demo-id").getProviderStateConsistent()).isFalse();
    }

    @Test void realModeNeverContactsProvider() {
        snapshot("PENDING");
        var real = new PaymentReconciliationService(snapshots, provider, "real");
        assertThat(real.query("demo-id").getProviderAvailability()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(provider);
    }

    @Test void unauthorizedRequestNeverConsultsAdapter() {
        when(snapshots.readAuthorized("demo-id")).thenThrow(new AccessDeniedException("Access denied"));
        assertThatThrownBy(() -> service.query("demo-id")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(provider);
    }

    @Test void historicalUnknownBalanceDoesNotBecomeConfirmedFromProviderStatus() {
        when(snapshots.readAuthorized("demo-id")).thenReturn(new PaymentReconciliationSnapshot.Snapshot(
                "SUCCESSFUL", "demo-request", "demo-transaction", "demo-id", null, null, "UNKNOWN", "UNKNOWN", null));
        when(provider.queryTransaction("demo-transaction")).thenReturn(metadata("Successful"));
        var result = service.query("demo-id");
        assertThat(result.getConfirmedAmount()).isNull();
        assertThat(result.getRemainingAmount()).isNull();
        assertThat(result.getBalanceBasis()).isEqualTo("UNKNOWN");
    }
}
