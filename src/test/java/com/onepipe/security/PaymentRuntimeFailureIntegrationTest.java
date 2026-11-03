package com.onepipe.security;

import com.onepipe.testsupport.ManualScheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.config.JwtService;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.service.PaymentAccounting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ManualScheduling
@SpringBootTest(properties = {"spring.config.location=classpath:/", "app.demo.payments.simulation.enabled=true"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(initializers = PaymentRuntimeFailureIntegrationTest.IsolatedEnvironment.class)
class PaymentRuntimeFailureIntegrationTest {
    private static final String PATH = "/api/dev/payment-simulations";
    private static final BigDecimal TOTAL = new BigDecimal("100000.00");
    private static final byte[] JWT_KEY = privateKey();
    private static final String DATABASE = "jdbc:h2:mem:runtime-rollback-" + UUID.randomUUID();

    @DynamicPropertySource
    static void isolatedSettings(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(JWT_KEY));
        registry.add("spring.datasource.url", () -> DATABASE);
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @SpyBean MockOnepipeClient provider;
    @SpyBean PaymentAccounting accounting;

    private User administrator;
    private Payment payment;

    @BeforeEach
    void fixtures() {
        clearDatabase();
        administrator = users.save(User.builder().email("rollback-admin@example.invalid")
                .password(UUID.randomUUID().toString()).role(Role.SUPER_ADMIN).build());
        payment = payments.save(Payment.builder().category(PaymentCategory.ADHOC_PAYMENT)
                .paymentType(PaymentType.SINGLE_PAYMENT).status(PaymentStatus.PENDING)
                .totalAmount(TOTAL).completedPayments(0).accountingVersion(1)
                .virtualAccountExpiryDate("2099-01-01 12:00:00")
                .requestRef(UUID.randomUUID().toString()).transactionRef("DEMO-" + UUID.randomUUID())
                .onePipePaymentId("MOCK-" + UUID.randomUUID()).build());
        clearInvocations(provider);
    }

    @AfterEach
    void cleanup() { clearDatabase(); }

    @Test
    void unexpectedFailureAfterBothWritesReturnsSafe500AndRollsBackTheEntireCollection() throws Exception {
        AtomicBoolean reachedPersistedChanges = new AtomicBoolean();
        String privateMarker = "synthetic-private-marker-" + UUID.randomUUID();
        String internalFailure = "SELECT * FROM payment_collections; transaction=" + payment.getTransactionRef()
                + "; provider=" + payment.getOnePipePaymentId() + "; secret=" + privateMarker;
        doAnswer(invocation -> {
            PaymentAccounting.Progress progress = (PaymentAccounting.Progress) invocation.callRealMethod();
            Payment current = invocation.getArgument(0);
            if (current.getStatus() == PaymentStatus.SUCCESSFUL && progress.confirmedAmount().signum() > 0) {
                // This final result snapshot runs after both repository saveAndFlush calls.
                assertThat(collectionCount()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class,
                        payment.getId())).isEqualTo("SUCCESSFUL");
                reachedPersistedChanges.set(true);
                throw new RuntimeException(internalFailure);
            }
            return progress;
        }).when(accounting).snapshot(any(Payment.class));
        List<?> before = databaseSnapshot();

        String response = http.perform(request("FULL"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.path").value(PATH))
                .andReturn().getResponse().getContentAsString();

        assertThat(reachedPersistedChanges).isTrue();
        assertThat(response).doesNotContain(internalFailure, privateMarker, payment.getTransactionRef(),
                payment.getOnePipePaymentId(), "SELECT", "payment_collections", "RuntimeException",
                "stackTrace", "exception", "secret=");
        assertUnchanged(before, PaymentStatus.PENDING);
    }

    @Test
    void invalidPaymentSlotRemains400WithoutAnyFinancialSideEffects() throws Exception {
        List<?> before = databaseSnapshot();
        http.perform(request("CYCLE_1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertUnchanged(before, PaymentStatus.PENDING);
    }

    @Test
    void paymentStateConflictRemains409WithoutAnyFinancialSideEffects() throws Exception {
        payment.setStatus(PaymentStatus.CANCELLED);
        payment = payments.saveAndFlush(payment);
        List<?> before = databaseSnapshot();
        http.perform(request("FULL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        assertUnchanged(before, PaymentStatus.CANCELLED);
    }

    private void assertUnchanged(List<?> before, PaymentStatus expectedStatus) {
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(collectionCount()).isZero();
        Payment unchanged = payments.findById(payment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(expectedStatus);
        assertThat(unchanged.getCompletedPayments()).isZero();
        PaymentAccounting.Progress progress = accounting.snapshot(unchanged);
        assertThat(progress.confirmedAmount()).isEqualByComparingTo("0.00");
        assertThat(progress.remainingAmount()).isEqualByComparingTo(TOTAL);
        verifyNoInteractions(provider);
    }

    private MockHttpServletRequestBuilder request(String slot) throws Exception {
        var principal = org.springframework.security.core.userdetails.User.withUsername(administrator.getEmail())
                .password(administrator.getPassword()).roles("SUPER_ADMIN").build();
        return post(PATH).header("Authorization", "Bearer " + jwt.generateToken(principal))
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "transactionRef", payment.getTransactionRef(), "collectionSlot", slot)));
    }

    private List<?> databaseSnapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM payments ORDER BY id"),
                jdbc.queryForList("SELECT * FROM payment_collections ORDER BY id"),
                jdbc.queryForList("SELECT * FROM app_users ORDER BY id"));
    }

    private long collectionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_collections", Long.class);
    }

    private void clearDatabase() {
        jdbc.update("DELETE FROM payment_collections");
        payments.deleteAll();
        users.deleteAll();
    }

    private static byte[] privateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
