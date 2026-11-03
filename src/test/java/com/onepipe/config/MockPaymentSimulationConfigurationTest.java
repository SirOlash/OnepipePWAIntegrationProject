package com.onepipe.config;

import com.onepipe.controllers.MockPaymentSimulationController;
import com.onepipe.security.ResourceAuthorization;
import com.onepipe.service.MockPaymentSimulationService;
import com.onepipe.service.PaymentEventProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockPaymentSimulationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withBean(ResourceAuthorization.class, () -> mock(ResourceAuthorization.class))
            .withBean(PaymentEventProcessor.class, () -> mock(PaymentEventProcessor.class))
            .withUserConfiguration(MockPaymentSimulationController.class, MockPaymentSimulationService.class);

    @ParameterizedTest
    @CsvSource({"dev,mock,true,true", "dev,MOCK,true,true", "dev,mock,false,false",
            "dev,real,true,false", "test,mock,true,false", "hosted,mock,true,false",
            "unknown,mock,true,false", "dev,mock,invalid,false", "dev,mock,TRUE,true"})
    void simulationRequiresAllThreeIndependentConditions(String profile, String mode, String enabled, boolean expected) {
        runner.withPropertyValues("spring.profiles.active=" + profile, "onepipe.mode=" + mode,
                "app.demo.payments.simulation.enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.containsBean("mockPaymentSimulationController")).isEqualTo(expected);
            assertThat(context.containsBean("mockPaymentSimulationService")).isEqualTo(expected);
        });
    }

    @Test
    void defaultDevModeDoesNotExposeSimulation() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context).doesNotHaveBean(MockPaymentSimulationController.class)
                        .doesNotHaveBean(MockPaymentSimulationService.class));
    }

    @Test
    void missingProfileDoesNotExposeSimulation() {
        runner.withPropertyValues("onepipe.mode=mock", "app.demo.payments.simulation.enabled=true").run(context ->
                assertThat(context).doesNotHaveBean(MockPaymentSimulationController.class));
    }

    @Test
    void mixedDevAndHostedNeverExposeSimulationEvenBeforeEnvironmentValidation() {
        runner.withPropertyValues("spring.profiles.active=dev,hosted", "onepipe.mode=mock",
                "app.demo.payments.simulation.enabled=true").run(context ->
                assertThat(context).doesNotHaveBean(MockPaymentSimulationController.class)
                        .doesNotHaveBean(MockPaymentSimulationService.class));
    }

    @Test
    void serviceRechecksEnvironmentAtInvocationBeforeCallingProcessor() {
        runner.withPropertyValues("spring.profiles.active=dev", "onepipe.mode=mock",
                "app.demo.payments.simulation.enabled=true").run(context -> {
            var service = context.getBean(MockPaymentSimulationService.class);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("disabled-after-creation",
                    Map.of("app.demo.payments.simulation.enabled", "false")));
            assertThatThrownBy(() -> service.simulate("synthetic-reference", "FULL"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(context.getBean(ResourceAuthorization.class)).requireSuperAdmin();
            verifyNoInteractions(context.getBean(PaymentEventProcessor.class));
        });
    }
}
