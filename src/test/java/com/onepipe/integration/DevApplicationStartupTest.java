package com.onepipe.integration;

import com.onepipe.testsupport.ManualScheduling;

import com.onepipe.config.DataSeeder;
import com.onepipe.controllers.TestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@ManualScheduling
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@ContextConfiguration(initializers = DevApplicationStartupTest.IsolatedEnvironment.class)
class DevApplicationStartupTest {

    @DynamicPropertySource
    static void localJwtKey(DynamicPropertyRegistry properties) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        properties.add("JWT_SECRET", () -> encodedKey);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private DataSource dataSource;

    @Test
    void devProfileStartsWithInMemoryH2AndServesExistingPublicBranchContract() throws SQLException {
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("dev");
        assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:greenfield-dev");
        }
        assertThat(context.getBeansOfType(OnepipeClient.class)).hasSize(1);
        assertThat(context.getBean(OnepipeClient.class)).isInstanceOf(MockOnepipeClient.class);
        assertThat(context.getBeansOfType(RealOnePipeClient.class)).isEmpty();
        assertThat(context.getBeansOfType(TestController.class)).isEmpty();
        assertThat(context.getBeansOfType(DataSeeder.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("ONEPIPE_API_KEY")).isNull();
        assertThat(context.getEnvironment().getProperty("ONEPIPE_CLIENT_SECRET")).isNull();
        assertThat(context.getEnvironment().getProperty("ONEPIPE_BASE_URL")).isNull();
        assertThat(context.getEnvironment().getProperty("ONEPIPE_WEBHOOK_URL")).isNull();

        ResponseEntity<String> response = http.getForEntity("/api/branches", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            applicationContext.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            applicationContext.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
