package com.onepipe.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.config.JwtService;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.integration.MockOnepipeClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.location=classpath:/", "app.demo.payments.simulation.enabled=true"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(initializers = MockPaymentSimulationIntegrationTest.IsolatedEnvironment.class)
class MockPaymentSimulationIntegrationTest {
    private static final String PATH = "/api/dev/payment-simulations";
    private static final byte[] JWT_KEY = privateKey();
    private static final String DATABASE = "jdbc:h2:mem:simulation-" + UUID.randomUUID();

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
    private final Map<Role, User> actors = new EnumMap<>(Role.class);
    private Payment payment;

    @BeforeEach
    void fixtures() {
        clearDatabase();
        for (Role role : Role.values()) {
            actors.put(role, users.save(User.builder().email(role.name().toLowerCase() + "@example.invalid")
                    .password(UUID.randomUUID().toString()).role(role).build()));
        }
        payment = payment(PaymentType.SINGLE_PAYMENT);
        clearInvocations(provider);
    }

    @AfterEach void cleanup() { clearDatabase(); }

    @Test
    void anonymousCallerCannotSimulateCollections() throws Exception {
        rejected(request("FULL"), 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARENT", "BRANCH_ADMIN"})
    void nonSuperAdministratorsCannotSimulateEvenForKnownInvoices(String role) throws Exception {
        rejected(as(Role.valueOf(role), request("FULL")), 403);
    }

    @Test
    void superAdministratorCanConfirmSinglePaymentWithoutAProviderCall() throws Exception {
        http.perform(as(Role.SUPER_ADMIN, request("FULL")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.confirmedAmount").value(100000))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.remainingAmount").value(0))
                .andExpect(jsonPath("$.eventId").isString());
        assertThat(collectionCount()).isEqualTo(1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
        verifyNoInteractions(provider);
    }

    @Test
    void freshSimulatedEventForAlreadyCollectedSlotCannotConfirmTwice() throws Exception {
        http.perform(as(Role.SUPER_ADMIN, request("FULL"))).andExpect(status().isOk());
        rejected(as(Role.SUPER_ADMIN, request("FULL")), 409);
        assertThat(collectionCount()).isEqualTo(1);
    }

    @Test
    void subscriptionRemainsActiveAfterTwoSequentialCycles() throws Exception {
        payment = payment(PaymentType.SUBSCRIPTION);
        http.perform(as(Role.SUPER_ADMIN, request("CYCLE_1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.remainingAmount").doesNotExist());
        http.perform(as(Role.SUPER_ADMIN, request("CYCLE_2"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.completedPayments").value(2));
        assertThat(collectionCount()).isEqualTo(2);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "currency", "status", "eventId", "source", "confirmedAt"})
    void callerCannotSupplyFinancialOrProviderClaims(String field) throws Exception {
        Map<String, Object> body = body("FULL"); body.put(field, "untrusted-claim");
        rejected(as(Role.SUPER_ADMIN, post(PATH).contentType("application/json").content(json.writeValueAsString(body))), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN", "INSTALLMENT_1", "CYCLE_1"})
    void invalidOrWrongTypeSlotsDoNotChangeFinancialState(String slot) throws Exception {
        rejected(as(Role.SUPER_ADMIN, request(slot)), 400);
    }

    @Test
    void unknownInvoiceDoesNotCreateCollections() throws Exception {
        Map<String, Object> body = body("FULL"); body.put("transactionRef", "missing-synthetic-reference");
        rejected(as(Role.SUPER_ADMIN, post(PATH).contentType("application/json").content(json.writeValueAsString(body))), 404);
    }

    private Payment payment(PaymentType type) {
        return payments.save(Payment.builder().category(PaymentCategory.ADHOC_PAYMENT).paymentType(type)
                .status(PaymentStatus.PENDING).totalAmount(new BigDecimal("100000.00"))
                .amountPerCycle(type == PaymentType.SUBSCRIPTION ? new BigDecimal("100000.00") : null)
                .installmentFrequency(type == PaymentType.SUBSCRIPTION ? InstallmentFrequency.MONTHLY : null)
                .completedPayments(0).accountingVersion(1).requestRef(UUID.randomUUID().toString())
                .billingAnchor(LocalDateTime.of(2025, 1, 31, 12, 0))
                .virtualAccountExpiryDate("2099-01-01 12:00:00")
                .transactionRef("DEMO-" + UUID.randomUUID()).onePipePaymentId("MOCK-" + UUID.randomUUID()).build());
    }

    private MockHttpServletRequestBuilder request(String slot) throws Exception {
        return post(PATH).contentType("application/json").content(json.writeValueAsString(body(slot)));
    }

    private Map<String, Object> body(String slot) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("transactionRef", payment.getTransactionRef());
        body.put("collectionSlot", slot); return body;
    }

    private MockHttpServletRequestBuilder as(Role role, MockHttpServletRequestBuilder request) {
        User user = actors.get(role);
        var principal = org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword()).roles(role.name()).build();
        return request.header("Authorization", "Bearer " + jwt.generateToken(principal));
    }

    private void rejected(MockHttpServletRequestBuilder request, int statusCode) throws Exception {
        List<?> before = snapshot(); clearInvocations(provider);
        http.perform(request).andExpect(status().is(statusCode));
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
    }

    private List<?> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM payments ORDER BY id"),
                jdbc.queryForList("SELECT * FROM payment_collections ORDER BY id"));
    }

    private long collectionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_collections", Long.class);
    }

    private void clearDatabase() {
        jdbc.update("DELETE FROM payment_collections"); payments.deleteAll(); users.deleteAll(); actors.clear();
    }

    private static byte[] privateKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }

    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
