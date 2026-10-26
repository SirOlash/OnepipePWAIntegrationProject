package com.onepipe.security;

import com.onepipe.testsupport.ManualScheduling;

import com.onepipe.config.JwtService;
import com.onepipe.data.entities.*;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Authorized HTTP queries against committed H2 ledger rows; Spring Security remains enabled. */
@ManualScheduling
@SpringBootTest(properties = "spring.config.location=classpath:/")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@ContextConfiguration(initializers = PaymentReconciliationIntegrationTest.IsolatedEnvironment.class)
class PaymentReconciliationIntegrationTest {
    private static final byte[] KEY = randomKey();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(KEY));
    }
    @Autowired MockMvc http;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired ParentRepository parents;
    @Autowired BranchRepository branches;
    @Autowired StudentRepository students;
    @Autowired PaymentRepository payments;
    @Autowired PaymentCollectionRepository collections;
    @Autowired JdbcTemplate jdbc;
    @SpyBean MockOnepipeClient provider;
    private Payment payment;
    private String token;

    @BeforeEach void fixtures() {
        clearDatabase();
        User superAdmin = users.save(User.builder().email("reconciliation-admin@example.invalid")
                .password(UUID.randomUUID().toString()).role(Role.SUPER_ADMIN).build());
        User parentUser = users.save(User.builder().email("reconciliation-parent@example.invalid")
                .password(UUID.randomUUID().toString()).role(Role.PARENT).build());
        Parent parent = parents.save(Parent.builder().user(parentUser).firstName("Synthetic").surname("Parent").build());
        Branch branch = branches.save(Branch.builder().businessName("Synthetic Campus").build());
        Student child = students.save(Student.builder().firstName("Synthetic").surname("Child")
                .studentRegId(UUID.randomUUID().toString()).parent(parent).branch(branch).classGrade(ClassGrade.JSS1).build());
        payment = payments.saveAndFlush(Payment.builder().student(child).parent(parent).branch(branch)
                .accountingVersion(1).paymentType(PaymentType.INSTALLMENT).category(PaymentCategory.ADHOC_PAYMENT)
                .status(PaymentStatus.ACTIVE).completedPayments(0).totalAmount(new BigDecimal("100000.00"))
                .downPaymentAmount(new BigDecimal("20000.00")).numberOfPayments(3)
                .transactionRef("DEMO-RECON-" + UUID.randomUUID()).requestRef(UUID.randomUUID().toString())
                .onePipePaymentId("DEMO-RECON-ID-" + UUID.randomUUID()).build());
        collections.saveAndFlush(PaymentCollection.builder().payment(payment).eventId("DEMO-EVENT-" + UUID.randomUUID())
                .transactionRef(payment.getTransactionRef()).collectionSlot("DOWN_PAYMENT")
                .collectionType(CollectionType.DOWN_PAYMENT).confirmedAmount(new BigDecimal("20000.00"))
                .currency("NGN").source(CollectionSource.MOCK).confirmedAt(Instant.parse("2026-01-01T00:00:00Z")).build());
        token = jwt.generateToken(org.springframework.security.core.userdetails.User.withUsername(superAdmin.getEmail())
                .password(superAdmin.getPassword()).roles("SUPER_ADMIN").build());
        clearInvocations(provider);
    }

    @AfterEach void cleanup() { reset(provider); clearDatabase(); }

    @Test void localLedgerWinsOverPendingMockAndQueryLeavesEveryPersistedValueUnchanged() throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return OnePipeResponse.builder().status("Pending").paymentId(payment.getOnePipePaymentId())
                    .transactionRef(payment.getTransactionRef()).build();
        }).when(provider).queryTransaction(payment.getTransactionRef());
        var before = snapshot();
        http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedAmount").value(20000.00))
                .andExpect(jsonPath("$.remainingAmount").value(80000.00))
                .andExpect(jsonPath("$.balanceBasis").value("CONFIRMED_COLLECTIONS"))
                .andExpect(jsonPath("$.providerAvailability").value("AVAILABLE"))
                .andExpect(jsonPath("$.providerStatus").value("PENDING"))
                .andExpect(jsonPath("$.providerStateConsistent").value(false))
                .andExpect(jsonPath("$.localStateConsistent").value(true))
                .andExpect(jsonPath("$.transactionRef").value(payment.getTransactionRef()))
                .andExpect(jsonPath("$.onePipePaymentId").value(payment.getOnePipePaymentId()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void missingMockMetadataAfterRestartDoesNotRecreateInvoiceOrEraseConfirmedCollections() throws Exception {
        // No invoice with this reference exists in this adapter instance.
        var before = snapshot();
        http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providerAvailability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedAmount").value(20000.00));
        assertThat(snapshot()).isEqualTo(before);
        verify(provider).queryTransaction(payment.getTransactionRef());
        verifyNoMoreInteractions(provider);
    }

    @Test void adapterExceptionIsSafeAndStillReturnsLocalAccountingWithoutMutation() throws Exception {
        doThrow(new IllegalStateException("PRIVATE SQL credential payment identifier"))
                .when(provider).queryTransaction(payment.getTransactionRef());
        var before = snapshot();
        var result = http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providerAvailability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.remainingAmount").value(80000.00)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE", "SQL", "credential");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void anonymousQueryCannotReachAdapterOrModifyLedger() throws Exception {
        var before = snapshot();
        http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId())).andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
    }

    @Test void historicalReportedSettlementRemainsUnknownEvenWhenMockAgrees() throws Exception {
        collections.deleteAll(); payment.setAccountingVersion(null); payment.setStatus(PaymentStatus.SUCCESSFUL);
        payments.saveAndFlush(payment);
        doReturn(OnePipeResponse.builder().status("Successful").paymentId(payment.getOnePipePaymentId())
                .transactionRef(payment.getTransactionRef()).build()).when(provider).queryTransaction(payment.getTransactionRef());
        var before = snapshot();
        http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.balanceBasis").value("UNKNOWN"))
                .andExpect(jsonPath("$.localStateConsistent").isEmpty())
                .andExpect(jsonPath("$.confirmedAmount").isEmpty()).andExpect(jsonPath("$.remainingAmount").isEmpty());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = PaymentStatus.class, names = {"PENDING", "SUCCESSFUL", "FAILED"})
    void inconsistentLocalLifecycleIsDiagnosedWithoutRepairingLedgerOrState(PaymentStatus state) throws Exception {
        payment.setStatus(state); payments.saveAndFlush(payment);
        doReturn(OnePipeResponse.builder().status(state.name()).paymentId(payment.getOnePipePaymentId())
                .transactionRef(payment.getTransactionRef()).build()).when(provider).queryTransaction(payment.getTransactionRef());
        var before = snapshot();
        http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state.name()))
                .andExpect(jsonPath("$.localStateConsistent").value(false))
                .andExpect(jsonPath("$.providerStateConsistent").value(true))
                .andExpect(jsonPath("$.confirmedAmount").value(20000.00))
                .andExpect(jsonPath("$.remainingAmount").value(80000.00));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void inconsistentLegacyCounterCannotReplaceLedgerCompletedCount() throws Exception {
        payment.setCompletedPayments(3); payments.saveAndFlush(payment);
        var before = snapshot();
        http.perform(post("/api/payments/{id}/query", payment.getOnePipePaymentId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.localStateConsistent").value(false))
                .andExpect(jsonPath("$.remainingAmount").value(80000.00));
        assertThat(snapshot()).isEqualTo(before);
    }

    private Object snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM payments ORDER BY id"),
                jdbc.queryForList("SELECT * FROM payment_collections ORDER BY id"));
    }
    private void clearDatabase() {
        collections.deleteAll(); payments.deleteAll(); students.deleteAll(); parents.deleteAll(); branches.deleteAll(); users.deleteAll();
    }
    private static byte[] randomKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
