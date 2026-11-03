package com.onepipe.config;

import com.onepipe.testsupport.ManualScheduling;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@ManualScheduling
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=classpath:/", "app.demo.seed.enabled=true"
})
@ActiveProfiles("dev")
@Import({DataSeeder.class, DataSeederIntegrationTest.PasswordConfiguration.class})
@ContextConfiguration(initializers = DataSeederIntegrationTest.IsolatedEnvironment.class)
// A test-managed transaction would mask whether the runner itself commits and rolls back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataSeederIntegrationTest {

    private static final String ADMIN_EMAIL = "super-admin@example.invalid";
    private static final String BRANCH_EMAIL = "branch-admin@example.invalid";
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String BRANCH_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void privateSeedSettings(DynamicPropertyRegistry properties) {
        properties.add("app.super-admin.email", () -> ADMIN_EMAIL);
        properties.add("app.super-admin.password", () -> ADMIN_PASSWORD);
        properties.add("app.demo.branch-admin.password", () -> BRANCH_PASSWORD);
    }

    @Autowired
    private DataSeeder seeder;

    @Autowired
    private UserRepository users;

    @SpyBean
    private BranchRepository branches;

    @SpyBean
    private PasswordEncoder encoder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearDatabase() {
        branches.deleteAll();
        users.deleteAll();
    }

    @Test
    void firstExecutionCommitsTwoHashedAccountsAndOneSyntheticBranch() {
        assertThat(AopUtils.isAopProxy(seeder)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        seeder.run();

        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
        User admin = user(ADMIN_EMAIL);
        User branchAdmin = user(BRANCH_EMAIL);
        assertThat(admin.getRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(branchAdmin.getRole()).isEqualTo(Role.BRANCH_ADMIN);
        assertThat(encoder.matches(ADMIN_PASSWORD, admin.getPassword())).isTrue();
        assertThat(encoder.matches(BRANCH_PASSWORD, branchAdmin.getPassword())).isTrue();
        assertThat(admin.getPassword()).startsWith("$2").isNotEqualTo(ADMIN_PASSWORD);
        assertThat(branchAdmin.getPassword()).startsWith("$2").isNotEqualTo(BRANCH_PASSWORD);
        Branch branch = branches.findAll().get(0);
        assertThat(branch.getAdminUser().getId()).isEqualTo(branchAdmin.getId());
        assertThat(branch.getRequestRef()).isEqualTo("DEMO-BRANCH-REF");
        assertThat(branch.getTransactionRef()).isEqualTo("DEMO-BRANCH-TXN");
        assertThat(branch.getRcNumber()).isEqualTo("DEMO-RC-001");
        assertThat(branch.getSettlementAccountNumber()).isEqualTo("0000000000");
        assertThat(branch.getBillerCode()).isEqualTo("DEMO-NOT-A-REAL-BILLER");
    }

    @Test
    void repeatedExecutionsKeepTheSameRowsPasswordsRolesAndAssociation() {
        seeder.run();
        User admin = user(ADMIN_EMAIL);
        User branchAdmin = user(BRANCH_EMAIL);
        Branch branch = branches.findAll().get(0);

        seeder.run();
        seeder.run();

        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
        assertUnchanged(admin);
        assertUnchanged(branchAdmin);
        Branch repeated = branches.findAll().get(0);
        assertThat(repeated.getId()).isEqualTo(branch.getId());
        assertThat(repeated.getUpdatedAt()).isEqualTo(branch.getUpdatedAt());
        assertThat(repeated.getAdminUser().getId()).isEqualTo(branchAdmin.getId());
    }

    @Test
    void anExistingSuperAdminKeepsItsDifferentPasswordAndRole() {
        User existing = saveUser(ADMIN_EMAIL, UUID.randomUUID().toString(), Role.SUPER_ADMIN);

        seeder.run();

        assertUnchanged(existing);
        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
    }

    @Test
    void anOrphanedBranchAdminIsReusedOnlyWithItsExistingPassword() {
        User existing = saveUser(BRANCH_EMAIL.toUpperCase(Locale.ROOT), BRANCH_PASSWORD, Role.BRANCH_ADMIN);

        seeder.run();
        seeder.run();

        assertUnchanged(existing);
        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
        assertThat(branches.findAll().get(0).getAdminUser().getId()).isEqualTo(existing.getId());
    }

    @Test
    void anOrphanedAccountWithAnotherPasswordIsNotTakenOverByEmail() {
        User existing = saveUser(BRANCH_EMAIL, UUID.randomUUID().toString(), Role.BRANCH_ADMIN);

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("existing password to prove ownership");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.count()).isZero();
        assertUnchanged(existing);
    }

    @Test
    void aCompleteBranchCanBeRetainedWhileTheMissingSuperAdminIsCreated() {
        User existing = saveUser(BRANCH_EMAIL, UUID.randomUUID().toString(), Role.BRANCH_ADMIN);
        Branch branch = branches.save(demoBranch(existing));

        seeder.run();

        assertUnchanged(existing);
        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
        assertThat(branches.findAll().get(0).getId()).isEqualTo(branch.getId());
    }

    @Test
    void aFullyIdentifiedUnownedDemoBranchCanReceiveANewDemoAdmin() {
        Branch branch = branches.save(demoBranch(null));

        seeder.run();
        seeder.run();

        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
        Branch repaired = branches.findById(branch.getId()).orElseThrow();
        assertThat(repaired.getAdminUser().getId()).isEqualTo(user(BRANCH_EMAIL).getId());
        assertThat(repaired.getBillerCode()).isEqualTo(branch.getBillerCode());
    }

    @Test
    void anUnownedDemoBranchCanBeLinkedToAnAuthenticatedOrphanedDemoAdmin() {
        User existing = saveUser(BRANCH_EMAIL, BRANCH_PASSWORD, Role.BRANCH_ADMIN);
        Branch branch = branches.save(demoBranch(null));

        seeder.run();

        assertUnchanged(existing);
        assertThat(branches.findById(branch.getId()).orElseThrow().getAdminUser().getId())
                .isEqualTo(existing.getId());
        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isEqualTo(1);
    }

    @Test
    void anAdminAlreadyAssignedToAnotherBranchIsNotReassigned() {
        User existing = saveUser(BRANCH_EMAIL, BRANCH_PASSWORD, Role.BRANCH_ADMIN);
        Branch unrelated = branches.save(Branch.builder().adminUser(existing)
                .businessName("Other Synthetic Campus").requestRef("OTHER-DEMO-REF").build());

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("already administers another branch");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.count()).isEqualTo(1);
        assertThat(branches.findById(unrelated.getId()).orElseThrow().getAdminUser().getId())
                .isEqualTo(existing.getId());
        assertUnchanged(existing);
    }

    @Test
    void theConfiguredSuperAdminCannotAlreadyOwnABranch() {
        User existing = saveUser(ADMIN_EMAIL, ADMIN_PASSWORD, Role.SUPER_ADMIN);
        branches.save(Branch.builder().adminUser(existing).businessName("Other Synthetic Campus").build());

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("super-admin already administers a branch");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.count()).isEqualTo(1);
        assertUnchanged(existing);
    }

    @Test
    void aDemoBranchOwnedByAnotherAccountIsNotTakenOver() {
        User unrelated = saveUser("other-admin@example.invalid", UUID.randomUUID().toString(), Role.BRANCH_ADMIN);
        Branch branch = branches.save(demoBranch(unrelated));

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("belongs to another account");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.findById(branch.getId()).orElseThrow().getAdminUser().getId())
                .isEqualTo(unrelated.getId());
        assertUnchanged(unrelated);
    }

    @Test
    void aReservedIdentifierOnAnUnrelatedBranchIsRejected() {
        Branch unrelated = branches.save(Branch.builder().businessName("Other Synthetic Campus")
                .requestRef("DEMO-BRANCH-REF").build());

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("identifiers conflict with existing branch data");

        assertThat(users.count()).isZero();
        assertThat(branches.count()).isEqualTo(1);
        assertThat(branches.findById(unrelated.getId()).orElseThrow().getAdminUser()).isNull();
    }

    @Test
    void reservedIdentifiersSplitAcrossBranchesAreRejected() {
        branches.save(Branch.builder().requestRef("DEMO-BRANCH-REF").build());
        branches.save(Branch.builder().rcNumber("DEMO-RC-001").build());

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("belong to different branches");

        assertThat(users.count()).isZero();
        assertThat(branches.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_EMAIL, BRANCH_EMAIL})
    void conflictingRolesRemainUnchangedWithoutCreatingOtherRecords(String email) {
        User existing = saveUser(email, UUID.randomUUID().toString(), Role.PARENT);

        assertThatIllegalStateException().isThrownBy(seeder::run).withMessageContaining("unexpected role");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.count()).isZero();
        assertUnchanged(existing);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_EMAIL, BRANCH_EMAIL})
    void ambiguousCaseInsensitiveIdentitiesAreRejected(String email) {
        Role role = email.equals(ADMIN_EMAIL) ? Role.SUPER_ADMIN : Role.BRANCH_ADMIN;
        User first = saveUser(email, UUID.randomUUID().toString(), role);
        User second = saveUser(email.toUpperCase(Locale.ROOT), UUID.randomUUID().toString(), role);

        assertThatIllegalStateException().isThrownBy(seeder::run).withMessageContaining("Multiple accounts match")
                .withMessageNotContaining(email);

        assertThat(users.count()).isEqualTo(2);
        assertThat(branches.count()).isZero();
        assertUnchanged(first);
        assertUnchanged(second);
    }

    @Test
    void editedDemoDataIsRejectedRatherThanOverwritten() {
        User existing = saveUser(BRANCH_EMAIL, BRANCH_PASSWORD, Role.BRANCH_ADMIN);
        Branch edited = demoBranch(existing);
        edited.setSettlementAccountNumber("CHANGED-DEMO-ACCOUNT");
        edited = branches.save(edited);

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessageContaining("identifiers conflict with existing branch data");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.findById(edited.getId()).orElseThrow().getSettlementAccountNumber())
                .isEqualTo("CHANGED-DEMO-ACCOUNT");
        assertUnchanged(existing);
    }

    @Test
    void aBranchFailureRollsBackBothNewAccountsThroughTheRunnerTransaction() {
        failBranchCreationAfterVerifyingUserInserts();

        assertThatIllegalStateException().isThrownBy(seeder::run).withMessage("Simulated branch write failure");

        assertThat(users.count()).isZero();
        assertThat(branches.count()).isZero();
    }

    @Test
    void aFailedRepairRollsBackTheNewSuperAdminAndPreservesTheExistingAccount() {
        User existing = saveUser(BRANCH_EMAIL, BRANCH_PASSWORD, Role.BRANCH_ADMIN);
        failBranchCreationAfterVerifyingUserInserts();

        assertThatIllegalStateException().isThrownBy(seeder::run).withMessage("Simulated branch write failure");

        assertThat(users.count()).isEqualTo(1);
        assertThat(branches.count()).isZero();
        assertUnchanged(existing);
    }

    @Test
    void aSecondAccountFailureRollsBackTheFirstAccount() {
        doThrow(new IllegalStateException("Simulated password hashing failure"))
                .when(encoder).encode(BRANCH_PASSWORD);

        assertThatIllegalStateException().isThrownBy(seeder::run)
                .withMessage("Simulated password hashing failure");

        assertThat(users.count()).isZero();
        assertThat(branches.count()).isZero();
    }

    @Test
    void aDatabaseFailureAtCommitRestoresAnUnownedBranchAndRollsBackNewAccounts() {
        Branch unowned = branches.save(demoBranch(null));
        // This H2-only constraint forces the relationship update to fail during commit.
        jdbc.execute("ALTER TABLE branches ADD CONSTRAINT test_reject_demo_owner CHECK (admin_user_id IS NULL)");
        try {
            assertThatThrownBy(seeder::run).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(users.count()).isZero();
            assertThat(branches.count()).isEqualTo(1);
            Branch original = branches.findById(unowned.getId()).orElseThrow();
            assertThat(original.getAdminUser()).isNull();
            assertThat(original.getUpdatedAt()).isEqualTo(unowned.getUpdatedAt());
        } finally {
            jdbc.execute("ALTER TABLE branches DROP CONSTRAINT test_reject_demo_owner");
        }
    }

    private void failBranchCreationAfterVerifyingUserInserts() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(users.count()).isEqualTo(2);
            throw new IllegalStateException("Simulated branch write failure");
        }).when(branches).save(any(Branch.class));
    }

    private User user(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User saveUser(String email, String password, Role role) {
        return users.save(User.builder().email(email).password(encoder.encode(password)).role(role).build());
    }

    private void assertUnchanged(User original) {
        User current = users.findById(original.getId()).orElseThrow();
        assertThat(current.getEmail()).isEqualTo(original.getEmail());
        assertThat(current.getPassword()).isEqualTo(original.getPassword());
        assertThat(current.getRole()).isEqualTo(original.getRole());
        assertThat(current.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
    }

    private Branch demoBranch(User owner) {
        return Branch.builder().adminUser(owner)
                .businessName("Greenfield Demo Campus").businessShortName("GRN-DEMO")
                .billerCode("DEMO-NOT-A-REAL-BILLER").contactEmail(BRANCH_EMAIL)
                .contactFirstName("Demo").contactSurname("Administrator")
                .contactPhoneNumber("00000000000").businessAddress("Demonstration Campus, Example City")
                .whatsappNumber("00000000000").settlementAccountNumber("0000000000").settlementBankCode("000")
                .rcNumber("DEMO-RC-001").tin("DEMO-TIN-001")
                .requestRef("DEMO-BRANCH-REF").transactionRef("DEMO-BRANCH-TXN").build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordConfiguration {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
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
