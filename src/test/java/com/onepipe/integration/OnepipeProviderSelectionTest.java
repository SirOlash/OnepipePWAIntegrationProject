package com.onepipe.integration;

import com.onepipe.config.OnepipeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OnepipeProviderSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    context.getEnvironment().getPropertySources()
                            .addLast(new ResourcePropertySource("classpath:application.properties"));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });

    private final ApplicationContextRunner providerRunner = contextRunner.withUserConfiguration(
            OnepipeConfiguration.class, MockOnepipeClient.class, RealOnePipeClient.class);

    @Test
    void checkedInConfigurationDefaultsToMockWithoutProviderSettings() {
        providerRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OnepipeClient.class)
                    .hasSingleBean(MockOnepipeClient.class).doesNotHaveBean(RealOnePipeClient.class);
            assertThat(context.getEnvironment().getProperty("onepipe.mode")).isEqualTo("mock");
            assertThat(context.getEnvironment().getProperty("ONEPIPE_API_KEY")).isNull();
            assertThat(context.getEnvironment().getProperty("ONEPIPE_CLIENT_SECRET")).isNull();
            assertThat(context.getEnvironment().getProperty("ONEPIPE_BASE_URL")).isNull();
            assertThat(context.getEnvironment().getProperty("ONEPIPE_WEBHOOK_URL")).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mock", "MOCK"})
    void explicitMockModeNeedsNoProviderSettings(String mode) {
        providerRunner.withPropertyValues("ONEPIPE_MODE=" + mode).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OnepipeClient.class)
                    .hasSingleBean(MockOnepipeClient.class).doesNotHaveBean(RealOnePipeClient.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"real", "REAL"})
    void realModeMustBeExplicitAndUsesOnlySuppliedFakeSettings(String mode) {
        providerRunner.withPropertyValues(
                "ONEPIPE_MODE=" + mode,
                "ONEPIPE_BASE_URL=https://provider.example.invalid",
                "ONEPIPE_API_KEY=" + UUID.randomUUID(),
                "ONEPIPE_CLIENT_SECRET=" + UUID.randomUUID(),
                "ONEPIPE_WEBHOOK_URL=https://callback.example.invalid/api/webhooks/onepipe"
        ).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OnepipeClient.class)
                    .hasSingleBean(RealOnePipeClient.class).doesNotHaveBean(MockOnepipeClient.class);
        });
    }

    @Test
    void explicitRealModeFailsWithoutProviderSettings() {
        providerRunner.withPropertyValues("ONEPIPE_MODE=real").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("ONEPIPE_BASE_URL");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "unknown", " real", "mock "})
    void unsupportedAndBlankModesFailWithAClearMessage(String mode) {
        providerRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("unsupported-mode", Map.of("ONEPIPE_MODE", mode))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Unsupported onepipe.mode");
                });
    }

    @Test
    void missingProviderFailsBeforeApplicationServicesAreCreated() {
        contextRunner.withUserConfiguration(OnepipeConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Exactly one OnepipeClient provider");
        });
    }

    @Test
    void multipleProvidersFailInsteadOfDependingOnPrimarySelection() {
        providerRunner.withUserConfiguration(AdditionalProvider.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Exactly one OnepipeClient provider");
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdditionalProvider {

        @Bean
        OnepipeClient additionalOnepipeClient() {
            return mock(OnepipeClient.class);
        }
    }
}
