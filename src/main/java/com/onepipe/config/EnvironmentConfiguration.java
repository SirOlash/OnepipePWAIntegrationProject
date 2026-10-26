package com.onepipe.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class EnvironmentConfiguration {

    @Bean
    static EnvironmentValidator validateEnvironment(Environment environment) {
        return new EnvironmentValidator(environment);
    }

    static final class EnvironmentValidator implements BeanFactoryPostProcessor, PriorityOrdered {

        private static final Set<String> PROFILES = Set.of("dev", "test", "hosted");
        private final Environment environment;

        EnvironmentValidator(Environment environment) {
            this.environment = environment;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length != 1 || !PROFILES.contains(profiles[0])) {
                throw new IllegalStateException("Activate exactly one supported Spring profile: dev, test, or hosted.");
            }

            rejectHikariConnectionOverrides();
            rejectFlywayConnectionOverrides();

            String profile = profiles[0];
            if (!"dev".equals(profile)) {
                String mode = setting("onepipe.mode");
                if (mode != null && !"mock".equalsIgnoreCase(mode)) {
                    throw new IllegalStateException("The " + profile + " profile requires onepipe.mode=mock.");
                }
            }

            if ("hosted".equals(profile)) {
                validateHosted();
            } else {
                validateH2(profile);
            }
        }

        private void rejectHikariConnectionOverrides() {
            for (String suffix : Set.of("jdbc-url", "driver-class-name", "username", "password",
                    "data-source-class-name", "data-source-jndi-name")) {
                String key = "spring.datasource.hikari." + suffix;
                ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
                for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
                    // Match the relaxed property names used by Boot without resolving connection values.
                    if (source.getConfigurationProperty(name) != null) {
                        throw new IllegalStateException("Connection overrides are not allowed for " + key + ".");
                    }
                }
            }
        }

        private void validateH2(String profile) {
            requireValue("spring.flyway.enabled", "false", profile);
            requireValue("spring.datasource.driver-class-name", "org.h2.Driver", profile);
            String url = setting("spring.datasource.url");
            if ((url != null && !url.startsWith("jdbc:h2:mem:")) || ("dev".equals(profile) && url == null)) {
                throw new IllegalStateException("The " + profile + " profile requires an in-memory H2 datasource URL.");
            }
            requireValue("spring.jpa.hibernate.ddl-auto", "create-drop", profile);

            if ("test".equals(profile)) {
                requireValue("app.demo.seed.enabled", "false", profile);
                if (url == null) {
                    requireValue("spring.datasource.generate-unique-name", "true", profile);
                }
            }
        }

        private void validateHosted() {
            requireNonblank("DB_URL");
            requireNonblank("DB_USERNAME");
            requireNonblank("DB_PASSWORD");
            requireNonblank("JWT_SECRET");

            String url = requireNonblank("spring.datasource.url");
            if (!url.startsWith("jdbc:postgresql:")) {
                throw new IllegalStateException("The hosted profile requires a PostgreSQL JDBC datasource URL.");
            }
            requireNonblank("spring.datasource.username");
            requireNonblank("spring.datasource.password");
            requireNonblank("security.jwt.secret-key");
            requireValue("spring.datasource.driver-class-name", "org.postgresql.Driver", "hosted");
            requireValue("spring.jpa.hibernate.ddl-auto", "validate", "hosted");
            requireValue("spring.flyway.enabled", "true", "hosted");
            requireValue("spring.flyway.baseline-on-migrate", "false", "hosted");
            requireValue("spring.flyway.clean-disabled", "true", "hosted");
            requireValue("spring.flyway.validate-on-migrate", "true", "hosted");
            requireValue("spring.flyway.fail-on-missing-locations", "true", "hosted");
            requireValue("spring.flyway.locations", "classpath:db/migration/postgresql", "hosted");
            requireValue("spring.jpa.defer-datasource-initialization", "false", "hosted");
            requireValue("spring.sql.init.mode", "never", "hosted");
        }

        private void rejectFlywayConnectionOverrides() {
            for (String suffix : Set.of("url", "user", "password", "driver-class-name")) {
                ConfigurationPropertyName name = ConfigurationPropertyName.of("spring.flyway." + suffix);
                for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
                    if (source.getConfigurationProperty(name) != null) {
                        throw new IllegalStateException("Flyway must use the application's datasource; separate connection settings are not allowed.");
                    }
                }
            }
        }

        private String requireNonblank(String name) {
            String value = setting(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("The hosted profile requires nonblank configuration for " + name + ".");
            }
            return value;
        }

        private void requireValue(String name, String expected, String profile) {
            if (!expected.equalsIgnoreCase(setting(name))) {
                throw new IllegalStateException("The " + profile + " profile requires " + name + "=" + expected + ".");
            }
        }

        private String setting(String name) {
            try {
                return environment.getProperty(name);
            } catch (IllegalArgumentException exception) {
                // Do not attach the original exception: placeholder errors can include configured values.
                throw new IllegalStateException("Unable to resolve configuration for " + name + ".");
            }
        }
    }
}
