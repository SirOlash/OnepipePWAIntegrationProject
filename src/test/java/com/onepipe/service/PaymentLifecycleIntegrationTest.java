package com.onepipe.service;

import com.onepipe.data.entities.*;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.dtos.request.PaymentRequest;
import com.onepipe.security.ResourceAuthorization;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The cancellation/expiry row lock is the same database lock used by collection confirmation. */
@SpringBootTest(properties = {"spring.config.location=classpath:/", "app.demo.payments.simulation.enabled=true"})
@ActiveProfiles("dev")
@ContextConfiguration(initializers = PaymentLifecycleIntegrationTest.IsolatedEnvironment.class)
class PaymentLifecycleIntegrationTest {
    private static final byte[] JWT_KEY = privateKey();
    private static final String DATABASE = "jdbc:h2:mem:lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final Instant NOW = Instant.parse("2026-04-30T12:00:00Z");
    private static final String SUPER = "lifecycle-super@example.invalid";
    @DynamicPropertySource static void settings(DynamicPropertyRegistry properties) {
        properties.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(JWT_KEY));
        properties.add("spring.datasource.url", () -> DATABASE);
    }

    @Autowired PaymentLifecycleService lifecycle;
    @Autowired PaymentService paymentService;
    @Autowired PaymentSchedule schedule;
    @Autowired ResourceAuthorization authorization;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PaymentEventProcessor events;
    @Autowired PaymentAccounting accounting;
    @Autowired PaymentRepository payments;
    @Autowired PaymentCollectionRepository collections;
    @Autowired UserRepository users;
    @Autowired ParentRepository parents;
    @Autowired BranchRepository branches;
    @Autowired StudentRepository students;
    @Autowired JdbcTemplate jdbc;
    @MockBean Clock clock;
    @SpyBean MockOnepipeClient provider;
    private Student student;

    @BeforeEach void fixtures() {
        cleanDatabase();
        when(clock.instant()).thenReturn(NOW); when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        User administrator = users.save(User.builder().email(SUPER).password(UUID.randomUUID().toString()).role(Role.SUPER_ADMIN).build());
        User parentUser = users.save(User.builder().email("lifecycle-parent@example.invalid").password(UUID.randomUUID().toString()).role(Role.PARENT).build());
        Parent parent = parents.save(Parent.builder().user(parentUser).firstName("Synthetic").surname("Parent").build());
        Branch branch = branches.save(Branch.builder().adminUser(administrator).businessName("Synthetic Campus").build());
        student = students.save(Student.builder().firstName("Synthetic").surname("Student").parent(parent).branch(branch)
                .studentRegId(UUID.randomUUID().toString()).classGrade(ClassGrade.JSS1).preferredPaymentType(PaymentType.SINGLE_PAYMENT).build());
        authenticate(); clearInvocations(provider);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); cleanDatabase(); }

    @ParameterizedTest @EnumSource(PaymentType.class)
    void expiryOnlyMarksTheInstructionAndPreservesEveryPlan(PaymentType type) {
        Payment payment = invoice(type); String before = financialSnapshot(payment);
        lifecycle.expireAll(); Payment expired = reload(payment);
        assertThat(expired.getVirtualAccountExpiredAt()).isEqualTo(NOW);
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        long version = expired.getRowVersion(); lifecycle.expireAll();
        assertThat(reload(payment).getRowVersion()).isEqualTo(version);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest @CsvSource({"INSTALLMENT,DAILY", "INSTALLMENT,WEEKLY", "INSTALLMENT,MONTHLY",
            "SUBSCRIPTION,DAILY", "SUBSCRIPTION,WEEKLY", "SUBSCRIPTION,MONTHLY"})
    void actualMockInvoiceSurvivesItsOneHourExpiryBeforeFirstRecurringDueDate(PaymentType type, InstallmentFrequency frequency) {
        PaymentRequest request = new PaymentRequest(); request.setStudentId(student.getId());
        request.setCategory(PaymentCategory.ADHOC_PAYMENT); request.setPaymentType(type);
        request.setAmount(new BigDecimal("100000.00")); request.setFrequency(frequency);
        if (type == PaymentType.INSTALLMENT) request.setNumberOfInstallments(3);
        var response = paymentService.triggerNewPayment(request);
        Payment payment = payments.findByOnePipePaymentId(response.getPaymentDetails().getOnePipePaymentId()).orElseThrow();
        assertThat(payment.getVirtualAccountExpiryDate()).isEqualTo("2026-04-30 14:00:00");
        assertThat(schedule.firstBillingDate(payment)).isAfter(LocalDateTime.of(2026, 4, 30, 14, 0));
        String before = financialSnapshot(payment); when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        lifecycle.expireAll();
        assertThat(reload(payment).getVirtualAccountExpiredAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(collections.count()).isZero(); verify(provider, never()).cancelMandate(any());
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 1})
    void expiryBoundaryIsInclusive(long seconds) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        when(clock.instant()).thenReturn(NOW.plusSeconds(seconds)); lifecycle.expireOne(payment.getId());
        assertThat(reload(payment).getVirtualAccountExpiredAt()).isEqualTo(seconds < 0 ? null : NOW);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"not-a-date", "2026-02-30 12:00:00", "2026-04-30T12:00:00Z"})
    void unknownExpiryIsSkippedWithoutChangingFinancialState(String expiry) {
        Payment payment = invoice(PaymentType.INSTALLMENT); payment.setVirtualAccountExpiryDate(expiry); payments.saveAndFlush(payment);
        String before = financialSnapshot(payment); lifecycle.expireAll();
        assertThat(reload(payment).getVirtualAccountExpiredAt()).isNull();
        assertThat(financialSnapshot(payment)).isEqualTo(before);
    }

    @Test void oneMalformedInstructionDoesNotPreventOtherExpiries() {
        Payment malformed = invoice(PaymentType.SINGLE_PAYMENT); malformed.setVirtualAccountExpiryDate("bad-date"); payments.saveAndFlush(malformed);
        Payment valid = invoice(PaymentType.SUBSCRIPTION); lifecycle.expireAll();
        assertThat(reload(malformed).getVirtualAccountExpiredAt()).isNull();
        assertThat(reload(valid).getVirtualAccountExpiredAt()).isEqualTo(NOW);
    }

    @Test void expiryAfterSettlementCannotUndoTheCollection() {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT);
        when(clock.instant()).thenReturn(NOW.minusSeconds(1)); confirm(payment, "settled", "FULL", "100000.00");
        when(clock.instant()).thenReturn(NOW.plusSeconds(1)); String before = financialSnapshot(payment);
        lifecycle.expireOne(payment.getId()); assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
        confirm(payment, "settled", "FULL", "100000.00");
        assertThat(financialSnapshot(payment)).isEqualTo(before);
    }

    @Test void activeInstallmentKeepsItsLedgerCounterAndRemainingAmountAfterExpiry() {
        Payment payment = invoice(PaymentType.INSTALLMENT); confirm(payment, "partial", "DOWN_PAYMENT", "20000.00");
        String before = financialSnapshot(payment); lifecycle.expireOne(payment.getId());
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThat(accounting.snapshot(reload(payment)).remainingAmount()).isEqualByComparingTo("80000.00");
    }

    @Test void activeSubscriptionSurvivesAccountExpiryBetweenConfirmedAndFutureCycles() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        payment.setBillingAnchor(LocalDateTime.of(2026, 3, 31, 12, 0)); payments.saveAndFlush(payment);
        confirm(payment, "active-expiry-cycle", "CYCLE_1", "100000.00");
        String before = financialSnapshot(payment); lifecycle.expireAll();
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        Payment saved = reload(payment); assertThat(saved.getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(saved.getVirtualAccountExpiredAt()).isEqualTo(NOW);
        assertThat(schedule.dueDate(saved, 2)).isAfter(schedule.anchorNow());
        assertThat(accounting.snapshot(saved).confirmedAmount()).isEqualByComparingTo("100000.00");
        assertThat(accounting.snapshot(saved).remainingAmount()).isNull(); verifyNoInteractions(provider);
    }

    @Test void recordedEventStillReplaysWhenTheHistoricalBillingAnchorIsUnavailable() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); confirm(payment, "recorded-before-anchor-loss", "CYCLE_1", "100000.00");
        Payment historical = reload(payment); historical.setBillingAnchor(null); payments.saveAndFlush(historical);
        String before = financialSnapshot(payment); confirm(payment, "recorded-before-anchor-loss", "CYCLE_1", "100000.00");
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThatThrownBy(() -> confirm(payment, "new-event-without-anchor", "CYCLE_2", "100000.00"))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); assertThat(collections.count()).isEqualTo(1);
    }

    @Test void lateOutOfOrderDueInstallmentsSettleTheExactLedgerTotalWithoutRescheduling() {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        String[] slots = {"INSTALLMENT_3", "DOWN_PAYMENT", "INSTALLMENT_1", "INSTALLMENT_2"};
        String[] amounts = {"26666.66", "20000.00", "26666.67", "26666.67"};
        String[] remaining = {"73333.34", "53333.34", "26666.67", "0.00"};
        int[] completed = {1, 1, 2, 3};
        for (int i = 0; i < slots.length; i++) {
            confirm(payment, "out-of-order-" + i, slots[i], amounts[i]);
            Payment saved = reload(payment);
            assertThat(saved.getStatus()).isEqualTo(i == 3 ? PaymentStatus.SUCCESSFUL : PaymentStatus.ACTIVE);
            assertThat(saved.getCompletedPayments()).isEqualTo(completed[i]);
            assertThat(accounting.snapshot(saved).remainingAmount()).isEqualByComparingTo(remaining[i]);
            assertThat(saved.getBillingAnchor()).isEqualTo(payment.getBillingAnchor());
        }
        assertThat(accounting.snapshot(reload(payment)).confirmedAmount()).isEqualByComparingTo("100000.00");
        assertThat(collections.findAll().stream().sorted(Comparator.comparing(PaymentCollection::getId)).toList())
                .extracting(PaymentCollection::getCollectionSlot).containsExactly(slots);
        verifyNoInteractions(provider);
    }

    @Test void cancellationBeforeFirstCycleAndRepeatedCancellationAreLocalAndIdempotent() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); lifecycle.cancel(payment.getId());
        Payment cancelled = reload(payment); assertThat(cancelled.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        long version = cancelled.getRowVersion(); lifecycle.cancel(payment.getId());
        assertThat(reload(payment).getRowVersion()).isEqualTo(version);
        assertThat(collections.count()).isZero(); verifyNoInteractions(provider);
    }

    @Test void owningParentCanCancelThroughPaymentServiceWithoutAnHttpPersistenceContext() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "lifecycle-parent@example.invalid", "unused", List.of(new SimpleGrantedAuthority("ROLE_PARENT"))));
        paymentService.cancelSubscription(payment.getOnePipePaymentId());
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(collections.count()).isZero(); verifyNoInteractions(provider);
    }

    @Test void anotherParentCannotCancelThroughPaymentServiceWithoutAnHttpPersistenceContext() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        User other = users.save(User.builder().email("lifecycle-other-parent@example.invalid")
                .password(UUID.randomUUID().toString()).role(Role.PARENT).build());
        parents.save(Parent.builder().user(other).firstName("Synthetic").surname("Other").build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                other.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_PARENT"))));
        String before = financialSnapshot(payment);
        assertThatThrownBy(() -> paymentService.cancelSubscription(payment.getOnePipePaymentId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); verifyNoInteractions(provider);
    }

    @Test void cancellationAfterCollectionsPreservesEvidenceAndLegitimateReplay() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); confirm(payment, "cycle-before-cancel", "CYCLE_1", "100000.00");
        lifecycle.cancel(payment.getId()); String before = financialSnapshot(payment);
        confirm(payment, "cycle-before-cancel", "CYCLE_1", "100000.00");
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThatThrownBy(() -> confirm(payment, "late-new-cycle", "CYCLE_2", "100000.00")).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); verifyNoInteractions(provider);
    }

    @ParameterizedTest @EnumSource(value = PaymentStatus.class, names = {"SUCCESSFUL", "FAILED"})
    void unsupportedSubscriptionTransitionsAreRejected(PaymentStatus status) {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); payment.setStatus(status); payments.saveAndFlush(payment);
        String before = financialSnapshot(payment);
        assertThatThrownBy(() -> lifecycle.cancel(payment.getId())).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); verifyNoInteractions(provider);
    }

    @Test void historicalUnknownRowsAreNotAdoptedOrCancelledOrExpired() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); payment.setAccountingVersion(null); payments.saveAndFlush(payment);
        String before = financialSnapshot(payment); lifecycle.expireAll();
        assertThatThrownBy(() -> lifecycle.cancel(payment.getId())).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThat(reload(payment).getVirtualAccountExpiredAt()).isNull();
        assertThat(accounting.snapshot(reload(payment)).confirmedAmount()).isNull();
    }

    @Test void realModeCancellationAndExpiryAreUnavailableWithoutAnyProviderCall() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); String before = financialSnapshot(payment);
        PaymentLifecycleService realMode = new PaymentLifecycleService(payments, authorization, accounting, schedule,
                new MockEnvironment().withProperty("onepipe.mode", "real"), transactionManager);
        assertThatThrownBy(() -> realMode.cancel(payment.getId())).isInstanceOf(ResourceConflictException.class);
        realMode.expireOne(payment.getId()); realMode.expireAll();
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        assertThat(reload(payment).getVirtualAccountExpiredAt()).isNull(); verifyNoInteractions(provider);
    }

    @Test void futureSubscriptionCycleIsRejectedWithoutAnyFinancialChange() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); payment.setBillingAnchor(schedule.anchorNow()); payments.saveAndFlush(payment);
        String before = financialSnapshot(payment);
        assertThatThrownBy(() -> confirm(payment, "future-cycle", "CYCLE_1", "100000.00")).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); assertThat(collections.count()).isZero();
    }

    @Test void subscriptionCycleBecomesEligibleExactlyAtItsCalendarDueDate() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        Instant due = Instant.parse("2026-02-28T11:00:00Z"); // Jan 31 noon Lagos -> Feb 28 noon Lagos.
        when(clock.instant()).thenReturn(due.minusNanos(1)); String before = financialSnapshot(payment);
        assertThatThrownBy(() -> confirm(payment, "before-due-cycle", "CYCLE_1", "100000.00")).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before);
        when(clock.instant()).thenReturn(due); confirm(payment, "exact-due-cycle", "CYCLE_1", "100000.00");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.ACTIVE); assertThat(collections.count()).isEqualTo(1);
        String after = financialSnapshot(payment);
        assertThatThrownBy(() -> confirm(payment, "future-second-cycle", "CYCLE_2", "100000.00")).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(after);
    }

    @Test void missingAnchorNeverCreatesAnInventedRecurringSchedule() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); payment.setBillingAnchor(null); payments.saveAndFlush(payment);
        String before = financialSnapshot(payment);
        assertThatThrownBy(() -> confirm(payment, "unknown-anchor", "CYCLE_1", "100000.00")).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); assertThat(collections.count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"2026-04-30 13:00:00", "unparseable", ""})
    void closedOrUnknownSingleWindowCannotAcceptANewSimulation(String expiry) {
        Payment payment = invoice(PaymentType.SINGLE_PAYMENT); payment.setVirtualAccountExpiryDate(expiry); payments.saveAndFlush(payment);
        String before = financialSnapshot(payment);
        assertThatThrownBy(() -> confirm(payment, "invalid-single-window", "FULL", "100000.00")).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); assertThat(collections.count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"pending-with-collection", "active-without-collection", "inconsistent-counter"})
    void contradictoryStateOrCountersCannotBeCancelled(String condition) {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        if (condition.equals("pending-with-collection")) {
            confirm(payment, "contradictory-cycle", "CYCLE_1", "100000.00"); payment = reload(payment);
            payment.setStatus(PaymentStatus.PENDING);
        } else if (condition.equals("active-without-collection")) payment.setStatus(PaymentStatus.ACTIVE);
        else payment.setCompletedPayments(3);
        payments.saveAndFlush(payment); String before = financialSnapshot(payment); Long id = payment.getId();
        assertThatThrownBy(() -> lifecycle.cancel(id)).isInstanceOf(ResourceConflictException.class);
        assertThat(financialSnapshot(payment)).isEqualTo(before); verifyNoInteractions(provider);
    }

    @Test void cancellationDatabaseFailureRollsBackWithoutTouchingProvider() {
        Payment payment = invoice(PaymentType.SUBSCRIPTION); confirm(payment, "existing-cycle", "CYCLE_1", "100000.00");
        String before = financialSnapshot(payment);
        jdbc.execute("ALTER TABLE payments ADD CONSTRAINT reject_lifecycle_cancellation CHECK (status <> 'CANCELLED')");
        try { assertThatThrownBy(() -> lifecycle.cancel(payment.getId())).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE payments DROP CONSTRAINT reject_lifecycle_cancellation"); }
        assertThat(financialSnapshot(payment)).isEqualTo(before); verifyNoInteractions(provider);
    }

    @Test void confirmationAndCancellationRaceSerializesWithoutReactivation() throws Exception {
        Payment payment = invoice(PaymentType.SUBSCRIPTION);
        List<Throwable> failures = race(() -> lifecycle.cancel(payment.getId()), () -> confirm(payment, "racing-cycle", "CYCLE_1", "100000.00"));
        assertThat(failures).allSatisfy(error -> assertThat(error).isInstanceOf(ResourceConflictException.class));
        assertThat(failures.size()).isLessThanOrEqualTo(1);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(collections.count()).isBetween(0L, 1L);
        assertThat(reload(payment).getCompletedPayments()).isEqualTo((int) collections.count());
        verifyNoInteractions(provider);
    }

    @Test void expiryAndPartialCollectionRacePreservesAccounting() throws Exception {
        Payment payment = invoice(PaymentType.INSTALLMENT);
        assertThat(race(() -> lifecycle.expireOne(payment.getId()), () -> confirm(payment, "racing-down", "DOWN_PAYMENT", "20000.00"))).isEmpty();
        Payment saved = reload(payment); assertThat(saved.getVirtualAccountExpiredAt()).isEqualTo(NOW);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(accounting.snapshot(saved).confirmedAmount()).isEqualByComparingTo("20000.00");
        assertThat(accounting.snapshot(saved).remainingAmount()).isEqualByComparingTo("80000.00");
        assertThat(collections.count()).isEqualTo(1); verifyNoInteractions(provider);
    }

    private Payment invoice(PaymentType type) {
        Payment payment = Payment.builder().student(student).parent(student.getParent()).branch(student.getBranch())
                .transactionRef("LIFECYCLE-" + UUID.randomUUID()).requestRef(UUID.randomUUID().toString())
                .onePipePaymentId("MOCK-" + UUID.randomUUID()).paymentType(type).status(PaymentStatus.PENDING)
                .totalAmount(new BigDecimal("100000.00")).completedPayments(0).accountingVersion(1)
                .billingAnchor(LocalDateTime.of(2026, 1, 31, 12, 0))
                .virtualAccountExpiryDate("2026-04-30 13:00:00")
                .downPaymentAmount(type == PaymentType.INSTALLMENT ? new BigDecimal("20000.00") : null)
                .amountPerCycle(type == PaymentType.SINGLE_PAYMENT ? null : new BigDecimal(type == PaymentType.INSTALLMENT ? "26666.67" : "100000.00"))
                .numberOfPayments(type == PaymentType.INSTALLMENT ? 3 : null)
                .installmentFrequency(type == PaymentType.SINGLE_PAYMENT ? null : InstallmentFrequency.MONTHLY).build();
        return payments.saveAndFlush(payment);
    }
    private void confirm(Payment payment, String id, String slot, String amount) {
        events.confirmMock(id, payment.getTransactionRef(), slot, new BigDecimal(amount), "NGN", NOW.minusSeconds(1));
    }
    private Payment reload(Payment payment) { return payments.findById(payment.getId()).orElseThrow(); }
    private String financialSnapshot(Payment payment) {
        Payment p = reload(payment);
        return List.of(String.valueOf(p.getStatus()), String.valueOf(p.getAccountingVersion()), String.valueOf(p.getCompletedPayments()),
                String.valueOf(p.getTotalAmount()), String.valueOf(p.getDownPaymentAmount()), String.valueOf(p.getAmountPerCycle()),
                String.valueOf(p.getNumberOfPayments()), String.valueOf(p.getBillingAnchor()), String.valueOf(accounting.snapshot(p)),
                String.valueOf(jdbc.queryForList("SELECT * FROM payment_collections ORDER BY id"))).toString();
    }
    private List<Throwable> race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (Runnable operation : List.of(first, second)) results.add(executor.submit(() -> {
                authenticate(); ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out"); operation.run(); return null; }
                catch (Throwable error) { return error; } finally { SecurityContextHolder.clearContext(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown(); List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> result : results) { Throwable error = result.get(20, TimeUnit.SECONDS); if (error != null) failures.add(error); }
            return failures;
        } finally { executor.shutdownNow(); }
    }
    private void authenticate() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(SUPER, "unused", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))); }
    private void cleanDatabase() { collections.deleteAll(); payments.deleteAll(); students.deleteAll(); branches.deleteAll(); parents.deleteAll(); users.deleteAll(); }
    private static byte[] privateKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
