package com.onepipe.integration;

import com.onepipe.controllers.TestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "onepipe.mode=mock",
        "app.demo.seed.enabled=false",
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:mock-startup;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class MockApplicationStartupTest {

    @DynamicPropertySource
    static void localJwtKey(DynamicPropertyRegistry properties) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        properties.add("security.jwt.secret-key", () -> encodedKey);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate http;

    @Test
    void applicationStartsWithH2AndServesExistingPublicBranchContract() {
        assertThat(context.getBeansOfType(OnepipeClient.class)).hasSize(1);
        assertThat(context.getBean(OnepipeClient.class)).isInstanceOf(MockOnepipeClient.class);
        assertThat(context.getBeansOfType(RealOnePipeClient.class)).isEmpty();
        assertThat(context.getBeansOfType(TestController.class)).isEmpty();

        ResponseEntity<String> response = http.getForEntity("/api/branches", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }
}
