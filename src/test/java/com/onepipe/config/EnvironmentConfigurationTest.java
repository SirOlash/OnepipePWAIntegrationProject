package com.onepipe.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.config.location=classpath:/")
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withUserConfiguration(EnvironmentConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "dev,test", "dev,hosted", "test,hosted", "dev,unknown"})
    void requiresExactlyOneExplicitSupportedProfileBeforeDatasourceInitialization(String profiles) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profiles)
                .withBean(DataSource.class, () -> {
                    throw new AssertionError("Datasource initialization must not run for an invalid profile selection");
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Activate exactly one supported Spring profile");
                });
    }

    @Test
    void devDefaultsToInMemoryMockWithoutExternalSettings() {
        contextRunner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class);
            assertThat(context.getEnvironment().getProperty("onepipe.mode")).isEqualTo("mock");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
            assertThat(context.getEnvironment().getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("org.h2.Driver");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
            assertThat(context.getEnvironment().getProperty("DB_URL")).isNull();
            assertThat(context.getEnvironment().getProperty("ONEPIPE_API_KEY")).isNull();
            assertThat(context.getEnvironment().getProperty("ONEPIPE_CLIENT_SECRET")).isNull();
        });
    }

    @Test
    void devPreservesExplicitRealAdapterOptIn() {
        contextRunner.withPropertyValues("spring.profiles.active=dev", "onepipe.mode=real").run(context ->
                assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @MethodSource("unsafeH2Overrides")
    void devAndTestRejectExternalOrPersistentDatasourceOverrides(String profile, String property, String value) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profile)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("unsafe-h2-override", Map.of(property, value))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("The " + profile + " profile requires");
                });
    }

    @Test
    void testProfileUsesDistinctGeneratedInMemoryDatabasesAcrossContexts() {
        ApplicationContextRunner testRunner = contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));
        AtomicReference<String> firstUrl = new AtomicReference<>();
        AtomicReference<String> secondUrl = new AtomicReference<>();

        testRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class);
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isNull();
            assertThat(context.getEnvironment().getProperty("spring.datasource.generate-unique-name")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
            assertThat(context.getEnvironment().getProperty("app.demo.seed.enabled")).isEqualTo("false");
            firstUrl.set(databaseUrl(context.getBean(DataSource.class)));
        });
        testRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class);
            secondUrl.set(databaseUrl(context.getBean(DataSource.class)));
        });

        assertThat(firstUrl.get()).startsWith("jdbc:h2:mem:");
        assertThat(secondUrl.get()).startsWith("jdbc:h2:mem:").isNotEqualTo(firstUrl.get());
    }

    @Test
    void testProfileAllowsAnExplicitIsolatedInMemoryH2Database() {
        contextRunner.withPropertyValues("spring.profiles.active=test",
                "spring.datasource.url=jdbc:h2:mem:explicit-" + UUID.randomUUID())
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "hosted"})
    void testAndHostedRejectAnExplicitRealProvider(String profile) {
        runnerFor(profile).withPropertyValues("onepipe.mode=real").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires onepipe.mode=mock");
        });
    }

    @Test
    void testProfileRejectsSeedingOverride() {
        contextRunner.withPropertyValues("spring.profiles.active=test", "app.demo.seed.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.demo.seed.enabled=false");
        });
    }

    @Test
    void testProfileRequiresGeneratedUniqueNamesWhenNoUrlIsSpecified() {
        contextRunner.withPropertyValues("spring.profiles.active=test", "spring.datasource.generate-unique-name=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("spring.datasource.generate-unique-name=true");
                });
    }

    @Test
    void hostedUsesOnlyExplicitPostgresSettingsAndSafePoolDefaultsWithoutConnecting() {
        hostedRunner(hostedSettings()).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class);
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).startsWith("jdbc:postgresql:");
            assertThat(context.getEnvironment().getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("org.postgresql.Driver");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getEnvironment().getProperty("onepipe.mode")).isEqualTo("mock");
            assertThat(context.getEnvironment().getProperty("app.demo.seed.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size"))
                    .isEqualTo("5");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("1");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.connection-timeout"))
                    .isEqualTo("30000");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.validation-timeout"))
                    .isEqualTo("5000");
        });
    }

    @ParameterizedTest
    @MethodSource("missingOrBlankHostedSettings")
    void hostedRejectsMissingOrBlankSettingsWithoutPrintingValues(String name, String value) {
        Map<String, String> settings = hostedSettings();
        if (value == null) {
            settings.remove(name);
        } else {
            settings.put(name, value);
        }

        hostedRunner(settings).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name);
            settings.values().stream().filter(setting -> !setting.isBlank()).forEach(setting ->
                    assertThat(context.getStartupFailure()).hasMessageNotContaining(setting));
        });
    }

    @ParameterizedTest
    @MethodSource("unsafeHostedOverrides")
    void hostedRejectsH2SchemaMutationAndBlankEffectiveSettings(String property, String value) {
        hostedRunner(hostedSettings()).withPropertyValues(property + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("hosted profile requires");
        });
    }

    @Test
    void unresolvedHostedPlaceholdersAreReportedWithoutTheirConfiguredValues() {
        String marker = "test-only-" + UUID.randomUUID();
        hostedRunner(hostedSettings()).withPropertyValues("spring.datasource.password=" + marker + "${MISSING_VALUE}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Unable to resolve configuration for spring.datasource.password")
                            .hasMessageNotContaining(marker)
                            .hasNoCause();
                });
    }

    @Test
    void hostedMapsRenderPortWithoutStartingAWebServer() {
        hostedRunner(hostedSettings()).withPropertyValues("PORT=12001").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("12001");
        });
    }

    @ParameterizedTest
    @MethodSource("hikariConnectionOverrides")
    void hikariConnectionOverridesFailBeforeDatasourceCreation(String profile, String property,
                                                              String canonicalProperty, String value) {
        runnerFor(profile)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("hikari-connection-override", Map.of(property, value))))
                .withBean(DataSource.class, () -> {
                    throw new AssertionError("Datasource initialization must not run for Hikari connection overrides");
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessage("Connection overrides are not allowed for " + canonicalProperty + ".")
                            .hasMessageNotContaining(value)
                            .hasNoCause();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "hosted"})
    void hikariEnvironmentAliasesFailBeforeDatasourceCreation(String profile) {
        String value = "jdbc:h2:tcp://remote-h2.example.invalid/must-not-connect";
        runnerFor(profile)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("SPRING_DATASOURCE_HIKARI_JDBCURL", value))))
                .withBean(DataSource.class, () -> {
                    throw new AssertionError("Datasource initialization must not run for Hikari environment overrides");
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessage("Connection overrides are not allowed for spring.datasource.hikari.jdbc-url.")
                            .hasMessageNotContaining(value)
                            .hasNoCause();
                });
    }

    @Test
    void hikariPoolTuningRemainsAllowed() {
        hostedRunner(hostedSettings()).withPropertyValues(
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.datasource.hikari.connection-timeout=15000"
        ).run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class));
    }

    private ApplicationContextRunner runnerFor(String profile) {
        return "hosted".equals(profile) ? hostedRunner(hostedSettings())
                : contextRunner.withPropertyValues("spring.profiles.active=" + profile);
    }

    private ApplicationContextRunner hostedRunner(Map<String, String> settings) {
        return contextRunner.withPropertyValues("spring.profiles.active=hosted")
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("hosted-test-settings", new HashMap<>(settings))));
    }

    private static Map<String, String> hostedSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("DB_URL", "jdbc:postgresql://postgres.example.invalid:5432/greenfield_demo");
        settings.put("DB_USERNAME", "test-only-" + UUID.randomUUID());
        settings.put("DB_PASSWORD", UUID.randomUUID().toString());
        settings.put("JWT_SECRET", Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
        return settings;
    }

    private static String databaseUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException exception) {
            throw new AssertionError("Unable to inspect the isolated test H2 datasource", exception);
        }
    }

    private static Stream<Arguments> missingOrBlankHostedSettings() {
        return Stream.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET")
                .flatMap(name -> Stream.of(Arguments.of(name, null), Arguments.of(name, ""), Arguments.of(name, " \t ")));
    }

    private static Stream<Arguments> unsafeH2Overrides() {
        return Stream.of("dev", "test").flatMap(profile -> Stream.of(
                Arguments.of(profile, "spring.datasource.url", "jdbc:postgresql://postgres.example.invalid/demo"),
                Arguments.of(profile, "spring.datasource.url", "jdbc:h2:file:./must-not-open"),
                Arguments.of(profile, "spring.datasource.driver-class-name", "org.postgresql.Driver"),
                Arguments.of(profile, "spring.jpa.hibernate.ddl-auto", "update")));
    }

    private static Stream<Arguments> unsafeHostedOverrides() {
        return Stream.of(
                Arguments.of("spring.datasource.url", "jdbc:h2:mem:must-not-open"),
                Arguments.of("spring.datasource.driver-class-name", "org.h2.Driver"),
                Arguments.of("spring.jpa.hibernate.ddl-auto", "create-drop"),
                Arguments.of("spring.jpa.hibernate.ddl-auto", "update"),
                Arguments.of("spring.datasource.username", ""),
                Arguments.of("spring.datasource.password", ""),
                Arguments.of("security.jwt.secret-key", ""));
    }

    private static Stream<Arguments> hikariConnectionOverrides() {
        return Stream.of("dev", "test", "hosted").flatMap(profile -> Stream.of(
                Arguments.of(profile, "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.jdbc-url",
                        "jdbc:h2:tcp://remote-h2.example.invalid/must-not-connect"),
                Arguments.of(profile, "spring.datasource.hikari.jdbcUrl", "spring.datasource.hikari.jdbc-url",
                        "jdbc:h2:tcp://remote-h2.example.invalid/must-not-connect"),
                Arguments.of(profile, "spring.datasource.hikari.driver-class-name", "spring.datasource.hikari.driver-class-name",
                        "org.h2.Driver"),
                Arguments.of(profile, "spring.datasource.hikari.username", "spring.datasource.hikari.username",
                        "test-only-" + UUID.randomUUID()),
                Arguments.of(profile, "spring.datasource.hikari.password", "spring.datasource.hikari.password",
                        UUID.randomUUID().toString()),
                Arguments.of(profile, "spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari.data-source-class-name",
                        "org.h2.jdbcx.JdbcDataSource"),
                Arguments.of(profile, "spring.datasource.hikari.data-source-jndi-name", "spring.datasource.hikari.data-source-jndi-name",
                        "java:comp/env/jdbc/test-only")));
    }
}
