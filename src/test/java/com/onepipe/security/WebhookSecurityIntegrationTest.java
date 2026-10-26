package com.onepipe.security;

import com.onepipe.testsupport.ManualScheduling;

import com.onepipe.config.JwtService;
import com.onepipe.data.entities.*;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.dtos.WebhookDto;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.service.WebhookService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual security filters and committed H2 writes; no simulated authentication filter bypass. */
@ManualScheduling
@SpringBootTest(properties = "spring.config.location=classpath:/")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@ContextConfiguration(initializers = WebhookSecurityIntegrationTest.IsolatedEnvironment.class)
@ExtendWith(OutputCaptureExtension.class)
class WebhookSecurityIntegrationTest {
    private static final String BODY_MARKER = "SYNTHETIC-RAW-WEBHOOK-DO-NOT-LOG";
    private static final String SIGNATURE_MARKER = "SYNTHETIC-SIGNATURE-DO-NOT-LOG";
    private static final byte[] JWT_KEY = randomKey();

    @DynamicPropertySource
    static void privateJwt(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(JWT_KEY));
    }

    @Autowired private MockMvc http;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ParentRepository parents;
    @Autowired private BranchRepository branches;
    @Autowired private StudentRepository students;
    @Autowired private PaymentRepository payments;
    @Autowired private WebhookService webhooks;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private MockOnepipeClient provider;
    private User superAdmin;
    private Payment invoice;

    @BeforeEach
    void fixtures() {
        clearDatabase();
        superAdmin = user("super", Role.SUPER_ADMIN);
        User parentUser = user("parent", Role.PARENT);
        Parent parent = parents.save(Parent.builder().user(parentUser).firstName("Synthetic")
                .surname("Parent").phoneNumber("DEMO-PHONE-WEBHOOK").build());
        Branch branch = branches.save(Branch.builder().adminUser(user("branch", Role.BRANCH_ADMIN))
                .businessName("Synthetic Campus").billerCode("MOCK-MERCHANT-WEBHOOK").build());
        Student student = students.save(Student.builder().parent(parent).branch(branch).firstName("Synthetic")
                .surname("Student").studentRegId(UUID.randomUUID().toString()).classGrade(ClassGrade.JSS1)
                .preferredPaymentType(PaymentType.SINGLE_PAYMENT).build());
        invoice = payments.save(Payment.builder().student(student).parent(parent).branch(branch)
                .category(PaymentCategory.ADHOC_PAYMENT).paymentType(PaymentType.SINGLE_PAYMENT)
                .status(PaymentStatus.PENDING).totalAmount(new BigDecimal("100000.00"))
                .completedPayments(0).transactionRef(UUID.randomUUID().toString())
                .requestRef(UUID.randomUUID().toString()).onePipePaymentId("MOCK-PAY-" + UUID.randomUUID()).build());
        clearInvocations(provider);
    }

    @AfterEach
    void cleanup() {
        reset(provider);
        clearDatabase();
    }

    @ParameterizedTest
    @ValueSource(strings = {"successful", "failed", "malformed", "empty"})
    void publicWebhookAlwaysRejectsWithoutMutationOrPayloadLogging(String submission, CapturedOutput output) throws Exception {
        String body = switch (submission) {
            case "malformed" -> "{BROKEN " + BODY_MARKER;
            case "empty" -> "";
            default -> "{\"details\":{\"status\":\"" + submission + "\",\"amount\":\"10000000\","
                    + "\"transaction_ref\":\"" + invoice.getTransactionRef() + "\",\"meta\":{\"payment_id\":\""
                    + invoice.getOnePipePaymentId() + "\",\"note\":\"" + BODY_MARKER + "\"}}}";
        };
        List<?> before = snapshot();
        http.perform(post("/api/webhooks/onepipe").contentType("application/json")
                        .header("Signature", SIGNATURE_MARKER).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WEBHOOK_VERIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(BODY_MARKER))))
                .andExpect(jsonPath("$.message").value("Payment webhook verification is unavailable"));
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
        assertThat(output.getAll()).doesNotContain(BODY_MARKER, SIGNATURE_MARKER);
    }

    @Test
    void unsignedWebhookIsNotAcknowledgedAsSuccessful() throws Exception {
        List<?> before = snapshot();
        http.perform(post("/api/webhooks/onepipe").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @Test
    void directServiceCallsFailClosedForMissingAndStructuredPayloads() {
        List<?> before = snapshot();
        WebhookDto payload = new WebhookDto();
        WebhookDto.Details details = new WebhookDto.Details();
        details.setStatus("Successful");
        details.setTransactionRef(invoice.getTransactionRef());
        WebhookDto.Meta meta = new WebhookDto.Meta();
        meta.setPaymentId(invoice.getOnePipePaymentId());
        details.setMeta(meta);
        payload.setDetails(details);
        assertThatThrownBy(() -> webhooks.processWebhook(payload))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Payment webhook verification is unavailable");
        assertThatThrownBy(() -> webhooks.processWebhook(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @MethodSource("untrustedQueryClaims")
    void queryNeverPromotesOrDemotesFinancialState(PaymentType type, PaymentStatus state, String providerClaim) throws Exception {
        invoice.setPaymentType(type);
        invoice.setStatus(state);
        invoice.setCompletedPayments(state == PaymentStatus.PENDING ? 0 : 2);
        invoice = payments.saveAndFlush(invoice);
        OnePipeResponse claim = new OnePipeResponse();
        claim.setStatus(providerClaim);
        doReturn(claim).when(provider).queryTransaction(anyString());
        List<?> before = snapshot();
        String token = jwt.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(superAdmin.getEmail()).password("unused").roles("SUPER_ADMIN").build());
        http.perform(post("/api/payments/{id}/query", invoice.getOnePipePaymentId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(state.name()))
                .andExpect(jsonPath("$.onePipePaymentId").value(invoice.getOnePipePaymentId()))
                .andExpect(jsonPath("$.transactionRef").value(invoice.getTransactionRef()))
                .andExpect(jsonPath("$.requestRef").value(invoice.getRequestRef()));
        assertThat(snapshot()).isEqualTo(before);
    }

    static Stream<Arguments> untrustedQueryClaims() {
        return Arrays.stream(PaymentType.values()).flatMap(type -> Arrays.stream(PaymentStatus.values())
                .flatMap(state -> Stream.of("Successful", "Failed").map(claim -> Arguments.of(type, state, claim))));
    }

    private User user(String label, Role role) {
        return users.save(User.builder().email("webhook-" + label + "@example.invalid")
                .password(UUID.randomUUID().toString()).role(role).build());
    }

    private List<?> snapshot() {
        return Stream.of("payments", "payment_collections")
                .map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    private void clearDatabase() {
        jdbc.update("DELETE FROM payment_collections");
        payments.deleteAll(); students.deleteAll(); parents.deleteAll(); branches.deleteAll(); users.deleteAll();
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key;
    }

    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
