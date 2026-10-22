package com.onepipe.service;

import com.onepipe.data.repositories.PaymentCollectionRepository;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.security.ResourceAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentCollectionGuardTest {
    @ParameterizedTest
    @CsvSource({"hosted,mock", "test,mock", "dev,real"})
    void directWorkerCallsCannotBypassProfileOrProviderGuards(String profile, String mode) {
        var environment = environment(profile, mode);
        var authorization = mock(ResourceAuthorization.class);
        var payments = mock(PaymentRepository.class);
        var collections = mock(PaymentCollectionRepository.class);
        var worker = new PaymentCollectionTransaction(payments, collections, new PaymentCalculator(),
                mock(PaymentAccounting.class), new PaymentCollectionGuard(environment, authorization));
        assertThatThrownBy(() -> worker.confirmMock("event", "reference", "FULL", BigDecimal.ONE, "NGN", Instant.now()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> worker.simulate("reference", "FULL")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(payments, collections, authorization);
    }

    @Test
    void directWorkerStillRequiresTheDatabaseSuperAdminRoleInEnabledDev() {
        var authorization = mock(ResourceAuthorization.class);
        doThrow(new AccessDeniedException("Access denied")).when(authorization).requireSuperAdmin();
        var payments = mock(PaymentRepository.class); var collections = mock(PaymentCollectionRepository.class);
        var worker = new PaymentCollectionTransaction(payments, collections, new PaymentCalculator(),
                mock(PaymentAccounting.class), new PaymentCollectionGuard(environment("dev", "mock"), authorization));
        assertThatThrownBy(() -> worker.simulate("reference", "FULL")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(payments, collections);
    }

    @Test
    void existingWorkerBecomesUnavailableWhenOptInIsRemoved() {
        var environment = environment("dev", "mock");
        var authorization = mock(ResourceAuthorization.class);
        var guard = new PaymentCollectionGuard(environment, authorization);
        guard.requireSimulation(); verify(authorization).requireSuperAdmin(); clearInvocations(authorization);
        environment.setProperty("app.demo.payments.simulation.enabled", "false");
        assertThatThrownBy(guard::requireSimulation).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(authorization);
    }

    private MockEnvironment environment(String profile, String mode) {
        var environment = new MockEnvironment().withProperty("onepipe.mode", mode)
                .withProperty("app.demo.payments.simulation.enabled", "true");
        environment.setActiveProfiles(profile); return environment;
    }
}
