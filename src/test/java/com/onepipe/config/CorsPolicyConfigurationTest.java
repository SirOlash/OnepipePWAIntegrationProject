package com.onepipe.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPolicyConfigurationTest {

    private static final String HOSTED_ORIGIN = "https://greenfieldboardingschool.vercel.app";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("spring.config.location=classpath:/")
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withUserConfiguration(CorsPolicyConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void localProfilesAllowOnlyTheTwoFrontendOrigins(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            CorsConfiguration configuration = configuration(context.getBean(CorsConfigurationSource.class));
            assertThat(configuration.getAllowedOrigins()).containsExactly(
                    "http://localhost:5173", "http://127.0.0.1:5173");
            assertThat(configuration.checkOrigin(HOSTED_ORIGIN)).isNull();
            assertSafeConfiguration(configuration);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void localOriginsCannotBeBroadenedByHostedSettings(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile,
                "CORS_ALLOWED_ORIGINS=*", "app.cors.allowed-origins=*").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(configuration(context.getBean(CorsConfigurationSource.class)).getAllowedOrigins())
                    .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
        });
    }

    @Test
    void hostedLoadsExactOriginsFromTheDocumentedEnvironmentVariable() {
        runner.withPropertyValues("spring.profiles.active=hosted", "CORS_ALLOWED_ORIGINS=" + HOSTED_ORIGIN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CorsConfiguration configuration = configuration(context.getBean(CorsConfigurationSource.class));
                    assertThat(configuration.getAllowedOrigins()).containsExactly(HOSTED_ORIGIN);
                    assertThat(configuration.checkOrigin(HOSTED_ORIGIN)).isEqualTo(HOSTED_ORIGIN);
                    assertThat(configuration.checkOrigin("https://another-project.vercel.app")).isNull();
                    assertThat(configuration.checkOrigin("https://greenfieldboardingschool.vercel.app.attacker.invalid"))
                            .isNull();
                    assertThat(configuration.checkOrigin("http://localhost:5173")).isNull();
                    assertThat(configuration.checkOrigin("null")).isNull();
                    assertSafeConfiguration(configuration);
                });
    }

    @Test
    void hostedSupportsAnExplicitCommaSeparatedAllowlistWithOptionalPorts() {
        runner.withPropertyValues("spring.profiles.active=hosted",
                "CORS_ALLOWED_ORIGINS= " + HOSTED_ORIGIN + " , http://review.example.invalid:5173 ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CorsConfiguration configuration = configuration(context.getBean(CorsConfigurationSource.class));
                    assertThat(configuration.getAllowedOrigins()).containsExactly(
                            HOSTED_ORIGIN, "http://review.example.invalid:5173");
                    assertThat(configuration.checkOrigin("http://review.example.invalid:5174")).isNull();
                });
    }

    @Test
    void hostedFailsWhenTheEnvironmentAllowlistIsAbsent() {
        runner.withPropertyValues("spring.profiles.active=hosted").run(context -> {
            assertThat(context).hasFailed();
            assertSafeFailure(context.getStartupFailure(), null);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "*", "https://*.vercel.app", "https://review*.example.invalid",
            "null", "review.example.invalid", "ftp://review.example.invalid", "//review.example.invalid",
            "https://review.example.invalid/", "https://review.example.invalid/path",
            "https://review.example.invalid?query=value", "https://review.example.invalid#fragment",
            "https://user:synthetic-password@review.example.invalid", "https://review.example.invalid:0",
            "https://review.example.invalid:-1", "https://review.example.invalid:65536",
            "https://review.example.invalid:invalid", "https://review.example.invalid:",
            "https://review.example.invalid,", ",https://review.example.invalid",
            "https://review.example.invalid,,https://other.example.invalid",
            "https://review.example.invalid;https://other.example.invalid",
            "https://review.example.invalid https://other.example.invalid",
            "https://review%2eexample.invalid", "https://review.example.invalid%2fpath",
            "https://review.example.invalid\\path", "https://review.example\n.invalid"
    })
    void hostedFailsClosedForMalformedOrWildcardOrigins(String origins) {
        runner.withPropertyValues("spring.profiles.active=hosted", "CORS_ALLOWED_ORIGINS=" + origins)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertSafeFailure(context.getStartupFailure(), origins);
                });
    }

    @Test
    void hostedAlsoValidatesTheEffectivePropertyWhenAnOverrideIsPresent() {
        runner.withPropertyValues("spring.profiles.active=hosted", "CORS_ALLOWED_ORIGINS=" + HOSTED_ORIGIN,
                "app.cors.allowed-origins=https://sensitive-config-marker.invalid/path").run(context -> {
            assertThat(context).hasFailed();
            assertSafeFailure(context.getStartupFailure(), "sensitive-config-marker");
        });
    }

    @Test
    void unresolvedPlaceholdersFailWithoutEchoingTheConfiguredValue() {
        runner.withPropertyValues("spring.profiles.active=hosted", "CORS_ALLOWED_ORIGINS=" + HOSTED_ORIGIN,
                "app.cors.allowed-origins=https://sensitive-config-marker.invalid/${MISSING_CORS_VALUE}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertSafeFailure(context.getStartupFailure(), "sensitive-config-marker");
                    assertSafeFailure(context.getStartupFailure(), "MISSING_CORS_VALUE");
                });
    }

    @Test
    void effectivePropertyCannotReplaceTheEnvironmentAllowlist() {
        runner.withPropertyValues("spring.profiles.active=hosted", "CORS_ALLOWED_ORIGINS=" + HOSTED_ORIGIN,
                "app.cors.allowed-origins=https://other.example.invalid").run(context -> {
            assertThat(context).hasFailed();
            assertSafeFailure(context.getStartupFailure(), "other.example.invalid");
        });
    }

    @Test
    void effectivePropertyCannotSupplyAMissingEnvironmentAllowlist() {
        runner.withPropertyValues("spring.profiles.active=hosted", "app.cors.allowed-origins=" + HOSTED_ORIGIN)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertSafeFailure(context.getStartupFailure(), null);
                });
    }

    private static CorsConfiguration configuration(CorsConfigurationSource source) {
        CorsConfiguration configuration = source.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/payments"));
        assertThat(configuration).isNotNull();
        return configuration;
    }

    private static void assertSafeConfiguration(CorsConfiguration configuration) {
        assertThat(configuration.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(configuration.getAllowCredentials()).isFalse();
        assertThat(configuration.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).containsExactlyInAnyOrder("Authorization", "Content-Type");
    }

    private static void assertSafeFailure(Throwable failure, String configuredValue) {
        assertThat(failure).isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (configuredValue != null && !configuredValue.isBlank() && configuredValue.length() > 1) {
                assertThat(cause.getMessage()).doesNotContain(configuredValue);
            }
        }
    }
}
