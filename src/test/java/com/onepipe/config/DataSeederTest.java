package com.onepipe.config;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataSeederTest {

    private static final String ADMIN_EMAIL = "super-admin@example.invalid";
    private static final String BRANCH_EMAIL = "branch-admin@example.invalid";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                context.getEnvironment().setActiveProfiles("dev");
            })
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(DataSeeder.class)
            .withBean(UserRepository.class, () -> userRepository)
            .withBean(BranchRepository.class, () -> branchRepository)
            .withBean(PasswordEncoder.class, () -> passwordEncoder);

    @Test
    void seedingIsDisabledWhenTheOptInPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSeeder.class);
            verifyNoInteractions(userRepository, branchRepository);
        });
    }

    @Test
    void seedingIsDisabledWhenTheOptInPropertyIsFalse() {
        contextRunner.withPropertyValues("app.demo.seed.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSeeder.class);
            verifyNoInteractions(userRepository, branchRepository);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test", "hosted", "unknown", "dev,test", "dev,hosted"})
    void automaticSeedingIsExcludedOutsideDevelopmentEvenWhenEnabled(String profiles) {
        contextRunner.withPropertyValues("app.demo.seed.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(
                        profiles.isEmpty() ? new String[0] : profiles.split(",")))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(DataSeeder.class);
                    verifyNoInteractions(userRepository, branchRepository);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.super-admin.email", "app.super-admin.password", "app.demo.branch-admin.password"})
    void missingRequiredSettingsFailBeforeRepositoryAccess(String missingProperty) {
        List<String> properties = new java.util.ArrayList<>(List.of("app.demo.seed.enabled=true"));
        java.util.Map.of(
                "app.super-admin.email", ADMIN_EMAIL,
                "app.super-admin.password", randomPassword(),
                "app.demo.branch-admin.password", randomPassword()
        ).forEach((name, value) -> {
            if (!name.equals(missingProperty)) {
                properties.add(name + "=" + value);
            }
        });
        contextRunner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSeeder.class);
            assertThatIllegalStateException().isThrownBy(() -> context.getBean(DataSeeder.class).run())
                    .withMessageContaining(missingProperty);
            verifyNoInteractions(userRepository, branchRepository);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.super-admin.email", "app.super-admin.password", "app.demo.branch-admin.password"})
    void unresolvedSeedSettingsDoNotExposeTheirSuppliedValues(String property) {
        String marker = "test-only-" + UUID.randomUUID();
        configuredRunner(ADMIN_EMAIL, randomPassword(), randomPassword())
                .withPropertyValues(property + "=" + marker + "${MISSING_DEMO_VALUE}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatIllegalStateException().isThrownBy(() -> context.getBean(DataSeeder.class).run())
                            .withMessageContaining(property).withMessageNotContaining(marker).withNoCause();
                    verifyNoInteractions(userRepository, branchRepository);
                });
    }

    @ParameterizedTest
    @MethodSource("blankRequiredSettings")
    void blankRequiredSettingsFailBeforeAccessingRepositories(String property, String value) {
        configuredRunner(ADMIN_EMAIL, randomPassword(), randomPassword())
                .withPropertyValues(property + "=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataSeeder.class);

                    assertThatIllegalStateException()
                            .isThrownBy(() -> context.getBean(DataSeeder.class).run())
                            .withMessageContaining(property);

                    verifyNoInteractions(userRepository, branchRepository);
                });
    }

    @Test
    void enabledSeedingHashesExplicitPasswordsAndCreatesSyntheticFixtures() {
        String adminPassword = randomPassword();
        String branchPassword = randomPassword();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        configuredRunner(" SUPER-ADMIN@EXAMPLE.INVALID ", adminPassword, branchPassword)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataSeeder.class);
                    context.getBean(DataSeeder.class).run();

                    ArgumentCaptor<User> users = ArgumentCaptor.forClass(User.class);
                    verify(userRepository, times(2)).save(users.capture());
                    List<User> savedUsers = users.getAllValues();
                    assertThat(savedUsers).extracting(User::getRole)
                            .containsExactlyInAnyOrder(Role.SUPER_ADMIN, Role.BRANCH_ADMIN);

                    User admin = savedUsers.stream().filter(user -> user.getRole() == Role.SUPER_ADMIN)
                            .findFirst().orElseThrow();
                    User branchAdmin = savedUsers.stream().filter(user -> user.getRole() == Role.BRANCH_ADMIN)
                            .findFirst().orElseThrow();
                    assertThat(admin.getEmail()).isEqualTo(ADMIN_EMAIL);
                    assertThat(branchAdmin.getEmail()).isEqualTo(BRANCH_EMAIL);
                    assertBcryptPassword(admin.getPassword(), adminPassword);
                    assertBcryptPassword(branchAdmin.getPassword(), branchPassword);

                    ArgumentCaptor<Branch> branches = ArgumentCaptor.forClass(Branch.class);
                    verify(branchRepository).save(branches.capture());
                    Branch branch = branches.getValue();
                    assertThat(branch.getAdminUser()).isSameAs(branchAdmin);
                    assertThat(branch.getBusinessName()).isEqualTo("Greenfield Demo Campus");
                    assertThat(branch.getContactEmail()).isEqualTo(BRANCH_EMAIL);
                    assertThat(branch.getBillerCode()).isEqualTo("DEMO-NOT-A-REAL-BILLER");
                    assertThat(branch.getContactPhoneNumber()).isEqualTo("00000000000");
                    assertThat(branch.getWhatsappNumber()).isEqualTo("00000000000");
                    assertThat(branch.getSettlementAccountNumber()).isEqualTo("0000000000");
                    assertThat(branch.getSettlementBankCode()).isEqualTo("000");
                });
    }

    @Test
    void existingAccountsKeepTheirPasswordsWithoutRepositoryWrites() {
        String originalAdminPassword = randomPassword();
        String originalBranchPassword = randomPassword();
        String adminHash = passwordEncoder.encode(originalAdminPassword);
        String branchHash = passwordEncoder.encode(originalBranchPassword);
        User admin = User.builder().email(ADMIN_EMAIL).password(adminHash).role(Role.SUPER_ADMIN).build();
        User branchAdmin = User.builder().email(BRANCH_EMAIL).password(branchHash).role(Role.BRANCH_ADMIN).build();
        admin.setId(1L);
        branchAdmin.setId(2L);
        Branch branch = demoBranch(branchAdmin);
        branch.setId(3L);
        when(userRepository.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(admin));
        when(userRepository.findByEmailIgnoreCase(BRANCH_EMAIL)).thenReturn(Optional.of(branchAdmin));
        when(branchRepository.findByAdminUser(branchAdmin)).thenReturn(Optional.of(branch));
        when(branchRepository.findByRequestRefOrTransactionRefOrRcNumber(
                "DEMO-BRANCH-REF", "DEMO-BRANCH-TXN", "DEMO-RC-001")).thenReturn(List.of(branch));

        configuredRunner(ADMIN_EMAIL, randomPassword(), randomPassword()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSeeder.class);
            DataSeeder seeder = context.getBean(DataSeeder.class);
            seeder.run();
            seeder.run();

            verify(userRepository, never()).save(any(User.class));
            verify(branchRepository, never()).save(any(Branch.class));
            assertThat(admin.getPassword()).isEqualTo(adminHash);
            assertThat(branchAdmin.getPassword()).isEqualTo(branchHash);
            assertThat(admin.getRole()).isEqualTo(Role.SUPER_ADMIN);
            assertThat(branchAdmin.getRole()).isEqualTo(Role.BRANCH_ADMIN);
            assertBcryptPassword(admin.getPassword(), originalAdminPassword);
            assertBcryptPassword(branchAdmin.getPassword(), originalBranchPassword);
        });
    }

    @ParameterizedTest
    @MethodSource("conflictingRoles")
    void conflictingRolesAreRejectedWithoutPromotingOrWritingAccounts(String email, Role role) {
        String originalHash = passwordEncoder.encode(randomPassword());
        User existing = User.builder().email(email).password(originalHash).role(role).build();
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(existing));

        configuredRunner(ADMIN_EMAIL, randomPassword(), randomPassword()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatIllegalStateException().isThrownBy(() -> context.getBean(DataSeeder.class).run())
                    .withMessageContaining("unexpected role");
            verify(userRepository, never()).save(any(User.class));
            verifyNoInteractions(branchRepository);
            assertThat(existing.getPassword()).isEqualTo(originalHash);
            assertThat(existing.getRole()).isEqualTo(role);
        });
    }

    @Test
    void conflictingSuperAdminAndBranchAdminEmailsFailBeforeAccessingRepositories() {
        configuredRunner(" BRANCH-ADMIN@EXAMPLE.INVALID ", randomPassword(), randomPassword())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataSeeder.class);

                    assertThatIllegalStateException()
                            .isThrownBy(() -> context.getBean(DataSeeder.class).run())
                            .withMessageContaining("emails must be different");

                    verifyNoInteractions(userRepository, branchRepository);
                });
    }

    private ApplicationContextRunner configuredRunner(String email, String adminPassword, String branchPassword) {
        return contextRunner.withPropertyValues(
                "app.demo.seed.enabled=true",
                "app.super-admin.email=" + email,
                "app.super-admin.password=" + adminPassword,
                "app.demo.branch-admin.password=" + branchPassword
        );
    }

    private void assertBcryptPassword(String hash, String rawPassword) {
        assertThat(hash).startsWith("$2").isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString();
    }

    private static Branch demoBranch(User owner) {
        return Branch.builder().adminUser(owner)
                .businessName("Greenfield Demo Campus").businessShortName("GRN-DEMO")
                .billerCode("DEMO-NOT-A-REAL-BILLER").contactEmail(BRANCH_EMAIL)
                .contactFirstName("Demo").contactSurname("Administrator")
                .contactPhoneNumber("00000000000").businessAddress("Demonstration Campus, Example City")
                .whatsappNumber("00000000000").settlementAccountNumber("0000000000").settlementBankCode("000")
                .rcNumber("DEMO-RC-001").tin("DEMO-TIN-001")
                .requestRef("DEMO-BRANCH-REF").transactionRef("DEMO-BRANCH-TXN").build();
    }

    private static Stream<Arguments> conflictingRoles() {
        return Stream.of(
                Arguments.of(ADMIN_EMAIL, Role.PARENT),
                Arguments.of(ADMIN_EMAIL, Role.BRANCH_ADMIN),
                Arguments.of(ADMIN_EMAIL, null),
                Arguments.of(BRANCH_EMAIL, Role.PARENT),
                Arguments.of(BRANCH_EMAIL, Role.SUPER_ADMIN),
                Arguments.of(BRANCH_EMAIL, null));
    }

    private static Stream<Arguments> blankRequiredSettings() {
        return Stream.of("app.super-admin.email", "app.super-admin.password", "app.demo.branch-admin.password")
                .flatMap(property -> Stream.of("", " \t ").map(value -> Arguments.of(property, value)));
    }
}
