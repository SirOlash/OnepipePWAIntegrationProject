package com.onepipe.service;

import com.onepipe.testsupport.ManualScheduling;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import com.onepipe.exceptions.customExceptions.ResourceNotFoundException;
import com.onepipe.integration.MockOnepipeClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real transactions and H2 constraints; no provider response is used as payment evidence. */
@ManualScheduling
@SpringBootTest(properties = {"spring.config.location=classpath:/", "app.demo.payments.simulation.enabled=true"})
@ActiveProfiles("dev")
@ContextConfiguration(initializers = PaymentCollectionIntegrationTest.IsolatedEnvironment.class)
class PaymentCollectionIntegrationTest {
    private static final byte[] JWT_KEY = randomKey();
    private static final String DATABASE = "jdbc:h2:mem:ledger-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-01-02T12:00:00Z");
    private static final String SUPER_EMAIL = "ledger-super@example.invalid";

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) {
        properties.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(JWT_KEY));
        properties.add("spring.datasource.url", () -> DATABASE);
    }

    @Autowired PaymentEventProcessor processor;
    @Autowired PaymentCollectionTransaction transaction;
    @Autowired PaymentAccounting accounting;
    @Autowired PaymentRepository payments;
    @Autowired PaymentCollectionRepository collections;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @SpyBean MockOnepipeClient provider;

    @BeforeEach void fixtures() {
        clearDatabase();
        users.save(User.builder().email(SUPER_EMAIL).password(UUID.randomUUID().toString()).role(Role.SUPER_ADMIN).build());
        authenticate();
        clearInvocations(provider);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        clearDatabase();
    }

    @Test void singleCollectionSettlesTheInvoiceAndPreservesTheLedgerEvidence() {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        confirm(payment, "single-event", "FULL", "100000.00");
        Payment saved = reload(payment);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
        assertThat(saved.getCompletedPayments()).isEqualTo(1);
        assertThat(accounting.snapshot(saved).confirmedAmount()).isEqualByComparingTo("100000.00");
        assertThat(accounting.snapshot(saved).remainingAmount()).isEqualByComparingTo("0.00");
        var entry = collections.findAll().get(0);
        assertThat(entry.getEventId()).isEqualTo("single-event");
        assertThat(entry.getTransactionRef()).isEqualTo(payment.getTransactionRef());
        assertThat(entry.getCollectionSlot()).isEqualTo("FULL");
        assertThat(entry.getCurrency()).isEqualTo("NGN");
        assertThat(entry.getSource().name()).isEqualTo("MOCK");
        assertThat(entry.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(entry.getCreatedAt()).isNotNull();
        verifyNoInteractions(provider);
    }

    @Test void downPaymentAndEveryExactInstallmentDeriveBalancesAndCounters() {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        String[] slots = {"DOWN_PAYMENT", "INSTALLMENT_1", "INSTALLMENT_2", "INSTALLMENT_3"};
        String[] amounts = {"20000.00", "26666.67", "26666.67", "26666.66"};
        String[] balances = {"80000.00", "53333.33", "26666.66", "0.00"};
        for (int i = 0; i < slots.length; i++) {
            confirm(payment, "installment-event-" + i, slots[i], amounts[i]);
            Payment saved = reload(payment);
            assertThat(saved.getStatus()).isEqualTo(i == 3 ? PaymentStatus.SUCCESSFUL : PaymentStatus.ACTIVE);
            // The down payment is a collection, but is not a recurring installment.
            assertThat(saved.getCompletedPayments()).isEqualTo(i);
            assertThat(accounting.snapshot(saved).remainingAmount()).isEqualByComparingTo(balances[i]);
            assertThat(accounting.snapshot(saved).confirmedAmount())
                    .isEqualByComparingTo(new BigDecimal("100000.00").subtract(new BigDecimal(balances[i])));
        }
        assertThat(collections.count()).isEqualTo(4);
        assertThat(collections.findAll().stream().sorted(Comparator.comparing(entry -> entry.getId())).toList())
                .extracting(entry -> entry.getConfirmedAmount())
                .containsExactly(new BigDecimal("20000.00"), new BigDecimal("26666.67"),
                        new BigDecimal("26666.67"), new BigDecimal("26666.66"));
        verifyNoInteractions(provider);
    }

    @Test void subscriptionCyclesRemainActiveWithoutAFiniteLifetimeBalance() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        for (int cycle = 1; cycle <= 3; cycle++) {
            confirm(payment, "cycle-event-" + cycle, "CYCLE_" + cycle, "100000.00");
            Payment saved = reload(payment);
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.ACTIVE);
            assertThat(saved.getCompletedPayments()).isEqualTo(cycle);
            assertThat(accounting.snapshot(saved).remainingAmount()).isNull();
            assertThat(accounting.snapshot(saved).balanceBasis()).isEqualTo("UNBOUNDED_SUBSCRIPTION");
        }
        assertThat(accounting.snapshot(reload(payment)).confirmedAmount()).isEqualByComparingTo("300000.00");
        assertThat(collections.count()).isEqualTo(3);
    }

    @Test void revenueUsesConfirmedPartialCollectionsAndCyclesWithoutTrustingLegacySettlement() {
        Payment historical = invoice(PaymentType.SINGLE_PAYMENT);
        historical.setAccountingVersion(null); historical.setStatus(PaymentStatus.SUCCESSFUL);
        historical.setTotalAmount(new BigDecimal("500.00")); payments.saveAndFlush(historical);
        assertThat(payments.calculateTotalRevenue()).isEqualByComparingTo("0.00");
        Payment installment = invoice(PaymentType.INSTALLMENT), subscription = invoice(PaymentType.SUBSCRIPTION);
        confirm(installment, "revenue-down", "DOWN_PAYMENT", "20000.00");
        confirm(subscription, "revenue-cycle", "CYCLE_1", "100000.00");
        assertThat(reload(installment).getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(reload(subscription).getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(payments.calculateTotalRevenue()).isEqualByComparingTo("120000.00");
        confirm(installment, "revenue-down", "DOWN_PAYMENT", "20000.00");
        assertThat(payments.calculateTotalRevenue()).isEqualByComparingTo("120000.00");
    }

    @Test void identicalReplayCannotDuplicateOrReactivateASettledPayment() {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        confirm(payment, "replayed-event", "FULL", "100000.00");
        var before = snapshot();
        confirm(payment, "replayed-event", "FULL", "100000.00");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
        verifyNoInteractions(provider);
    }

    @Test void differentEventForOccupiedSlotIsRejectedWithoutMutation() {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        confirm(payment, "first-down", "DOWN_PAYMENT", "20000.00");
        rejectUnchanged(() -> confirm(payment, "second-down", "DOWN_PAYMENT", "20000.00"));
    }

    @Test void sameEventCannotBeReusedForAnotherInvoice() {
        Payment first = invoice(PaymentType.SINGLE_PAYMENT), second = invoice(PaymentType.SINGLE_PAYMENT);
        confirm(first, "global-event", "FULL", "100000.00");
        rejectUnchanged(() -> confirm(second, "global-event", "FULL", "100000.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "currency", "slot", "timestamp"})
    void conflictingEventDetailsAreRejected(String field) {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        confirm(payment, "immutable-event", "DOWN_PAYMENT", "20000.00");
        rejectUnchanged(() -> processor.confirmMock("immutable-event", payment.getTransactionRef(),
                field.equals("slot") ? "INSTALLMENT_1" : "DOWN_PAYMENT",
                new BigDecimal(field.equals("amount") ? "20000.01" : "20000.00"),
                field.equals("currency") ? "USD" : "NGN",
                field.equals("timestamp") ? CONFIRMED_AT.plusSeconds(1) : CONFIRMED_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "100000.001", "100000.000", "99999.99", "100000.01"})
    void invalidPrecisionUnderpaymentsAndOverpaymentsNeverCreateCollections(String amount) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        rejectUnchanged(() -> confirm(payment, "invalid-money", "FULL", amount));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "ngn", "", " NGN "})
    void incorrectCurrencyCannotCreateACollection(String currency) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        rejectUnchanged(() -> processor.confirmMock("wrong-currency", payment.getTransactionRef(), "FULL",
                new BigDecimal("100000.00"), currency, CONFIRMED_AT));
    }

    @ParameterizedTest
    @CsvSource({"SINGLE_PAYMENT,DOWN_PAYMENT", "SINGLE_PAYMENT,CYCLE_1", "INSTALLMENT,INSTALLMENT_0",
            "INSTALLMENT,INSTALLMENT_4", "INSTALLMENT,FULL", "SUBSCRIPTION,CYCLE_0",
            "SUBSCRIPTION,CYCLE_2", "SUBSCRIPTION,FULL", "SUBSCRIPTION,CYCLE_01"})
    void unrecognizedOrNonSequentialSlotsAreRejected(PaymentType type, String slot) {
        Payment payment = invoice(type);
        rejectUnchanged(() -> confirm(payment, "invalid-slot", slot, "100000.00"));
    }

    @Test void unknownInvoiceCannotCreateACollection() {
        rejectUnchanged(() -> processor.confirmMock("unknown-invoice", "UNKNOWN-TRANSACTION", "FULL",
                new BigDecimal("100000.00"), "NGN", CONFIRMED_AT));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "FAILED", "SUCCESSFUL"})
    void terminalOrUnsupportedStatesCannotBeReactivated(PaymentStatus status) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        payment.setStatus(status); payments.saveAndFlush(payment);
        rejectUnchanged(() -> confirm(payment, "late-event", "FULL", "100000.00"));
        assertThat(reload(payment).getStatus()).isEqualTo(status);
    }

    @Test void historicalCountersNeverBecomeFabricatedLedgerEvidence() {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        payment.setAccountingVersion(null); payment.setStatus(PaymentStatus.ACTIVE); payment.setCompletedPayments(2);
        payments.saveAndFlush(payment);
        assertThat(accounting.snapshot(reload(payment)).remainingAmount()).isNull();
        assertThat(accounting.snapshot(reload(payment)).balanceBasis()).isEqualTo("UNKNOWN");
        rejectUnchanged(() -> confirm(payment, "legacy-event", "INSTALLMENT_3", "26666.66"));
        assertThat(reload(payment).getCompletedPayments()).isEqualTo(2);
    }

    @Test void recreatedServiceUsesPersistedEventIdentityRatherThanInMemoryState() {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        confirm(payment, "durable-event", "DOWN_PAYMENT", "20000.00");
        var before = snapshot();
        new PaymentEventProcessor(transaction).confirmMock("durable-event", payment.getTransactionRef(),
                "DOWN_PAYMENT", new BigDecimal("20000.00"), "NGN", CONFIRMED_AT);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(collections.count()).isEqualTo(1);
    }

    @Test void concurrentIdenticalDeliveryCommitsExactlyOneCollection() throws Exception {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        List<Throwable> failures = runConcurrently(6,
                () -> confirm(payment, "concurrent-event", "FULL", "100000.00"));
        assertThat(failures).isEmpty();
        assertThat(collections.count()).isEqualTo(1);
        assertThat(reload(payment).getCompletedPayments()).isEqualTo(1);
        assertThat(accounting.snapshot(reload(payment)).confirmedAmount()).isEqualByComparingTo("100000.00");
    }

    @Test void concurrentDistinctEventsCannotCollectTheSameSlotTwice() throws Exception {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        List<Throwable> failures = runConcurrently(6,
                () -> confirm(payment, "concurrent-" + UUID.randomUUID(), "FULL", "100000.00"));
        assertThat(failures).hasSize(5).allSatisfy(error -> assertThat(error).isInstanceOf(ResourceConflictException.class));
        assertThat(collections.count()).isEqualTo(1);
        assertThat(accounting.snapshot(reload(payment)).remainingAmount()).isEqualByComparingTo("0.00");
    }

    @Test void databaseEventIdentityProtectsConcurrentDifferentInvoices() throws Exception {
        Payment first = invoice(PaymentType.SINGLE_PAYMENT), second = invoice(PaymentType.SINGLE_PAYMENT);
        AtomicInteger request = new AtomicInteger();
        List<Throwable> failures = runConcurrently(2, () -> confirm(request.getAndIncrement() == 0 ? first : second,
                "cross-invoice-event", "FULL", "100000.00"));
        assertThat(failures).hasSize(1).allSatisfy(error -> assertThat(error).isInstanceOf(ResourceConflictException.class));
        assertThat(collections.count()).isEqualTo(1);
        assertThat(List.of(reload(first), reload(second))).extracting(Payment::getStatus)
                .containsExactlyInAnyOrder(PaymentStatus.PENDING, PaymentStatus.SUCCESSFUL);
        assertThat(payments.findAll().stream().map(Payment::getCompletedPayments).mapToInt(Integer::intValue).sum()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "transaction", "slot", "amount", "currency", "timestamp"})
    void missingTrustedEventFieldsNeverCreateCollectionEvidence(String missing) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        rejectUnchanged(() -> processor.confirmMock(missing.equals("event") ? null : "missing-field-event",
                missing.equals("transaction") ? null : payment.getTransactionRef(),
                missing.equals("slot") ? null : "FULL", missing.equals("amount") ? null : new BigDecimal("100000.00"),
                missing.equals("currency") ? null : "NGN", missing.equals("timestamp") ? null : CONFIRMED_AT));
    }

    @Test void failedPaymentUpdateRollsBackTheCollectionInsertionToo() {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        jdbc.execute("ALTER TABLE payments ADD CONSTRAINT task7_reject_settlement CHECK (status <> 'SUCCESSFUL')");
        try {
            rejectUnchanged(() -> confirm(payment, "rolled-back-event", "FULL", "100000.00"));
        } finally {
            jdbc.execute("ALTER TABLE payments DROP CONSTRAINT task7_reject_settlement");
        }
        assertThat(collections.count()).isZero();
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reload(payment).getCompletedPayments()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "FAILED", "PENDING"})
    void staleDetachedUpdatesCannotOverwriteConfirmedSettlement(PaymentStatus staleStatus) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        Payment stale = reload(payment);
        confirm(payment, "protected-settlement", "FULL", "100000.00");
        var before = snapshot();
        stale.setStatus(staleStatus);
        assertThatThrownBy(() -> payments.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
    }

    @ParameterizedTest
    @CsvSource({"0.00,NGN", "-0.01,NGN", "100000.00,USD"})
    void databaseConstraintsRejectInvalidMoneyEvenOutsideTheProcessor(String amount, String currency) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        var before = snapshot();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_collections "
                        + "(event_id,payment_id,transaction_ref,collection_slot,confirmed_amount,currency,source,confirmed_at,collection_type,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                "invalid-db-money", payment.getId(), payment.getTransactionRef(), "FULL", new BigDecimal(amount),
                currency, "MOCK", java.sql.Timestamp.from(CONFIRMED_AT), "FULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    private Payment invoice(PaymentType type) {
        return payments.saveAndFlush(Payment.builder().transactionRef("TASK7-" + UUID.randomUUID())
                .requestRef(UUID.randomUUID().toString()).paymentType(type).status(PaymentStatus.PENDING)
                .totalAmount(new BigDecimal("100000.00")).completedPayments(0).accountingVersion(1)
                .downPaymentAmount(type == PaymentType.INSTALLMENT ? new BigDecimal("20000.00") : null)
                .amountPerCycle(type == PaymentType.SINGLE_PAYMENT ? null : new BigDecimal(type == PaymentType.INSTALLMENT ? "26666.67" : "100000.00"))
                .numberOfPayments(type == PaymentType.INSTALLMENT ? 3 : null)
                .billingAnchor(LocalDateTime.of(2025, 1, 31, 12, 0))
                .virtualAccountExpiryDate("2099-01-01 12:00:00")
                .installmentFrequency(type == PaymentType.SINGLE_PAYMENT ? null : InstallmentFrequency.MONTHLY).build());
    }

    private void confirm(Payment payment, String eventId, String slot, String amount) {
        processor.confirmMock(eventId, payment.getTransactionRef(), slot, new BigDecimal(amount), "NGN", CONFIRMED_AT);
    }
    private Payment reload(Payment payment) { return payments.findById(payment.getId()).orElseThrow(); }
    private void rejectUnchanged(Runnable request) {
        var before = snapshot();
        assertThatThrownBy(request::run).isInstanceOfAny(InvalidPaymentException.class,
                ResourceConflictException.class, ResourceNotFoundException.class);
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
    }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM payments ORDER BY id"),
                jdbc.queryForList("SELECT * FROM payment_collections ORDER BY id"));
    }
    private List<Throwable> runConcurrently(int count, Runnable request) throws Exception {
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) results.add(executor.submit(() -> {
                authenticate(); ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out"); request.run(); return null; }
                catch (Throwable error) { return error; }
                finally { SecurityContextHolder.clearContext(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (var result : results) { Throwable error = result.get(20, TimeUnit.SECONDS); if (error != null) failures.add(error); }
            return failures;
        } finally { executor.shutdownNow(); }
    }
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                SUPER_EMAIL, "unused", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }
    private void clearDatabase() { collections.deleteAll(); payments.deleteAll(); users.deleteAll(); }
    private static byte[] randomKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
