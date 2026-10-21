package com.onepipe.config;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
@Profile("dev & !test & !hosted")
@ConditionalOnProperty(name = "app.demo.seed.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final String DEMO_BRANCH_EMAIL = "branch-admin@example.invalid";
    private static final String DEMO_REQUEST_REF = "DEMO-BRANCH-REF";
    private static final String DEMO_TRANSACTION_REF = "DEMO-BRANCH-TXN";
    private static final String DEMO_RC_NUMBER = "DEMO-RC-001";

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(String... args) {
        String adminEmail = requireSeedSetting("app.super-admin.email");
        String adminPassword = requireSeedSetting("app.super-admin.password");
        String branchAdminPassword = requireSeedSetting("app.demo.branch-admin.password");
        String normalizedEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
        if (DEMO_BRANCH_EMAIL.equalsIgnoreCase(normalizedEmail)) {
            throw new IllegalStateException("Demo super-admin and branch-admin emails must be different");
        }

        // Resolve and validate the complete existing state before making any changes.
        Optional<User> superAdmin = findUser(normalizedEmail, "super-admin");
        Optional<User> branchAdmin = findUser(DEMO_BRANCH_EMAIL, "branch-admin");
        superAdmin.ifPresent(user -> requireRole(user, Role.SUPER_ADMIN, "super-admin"));
        branchAdmin.ifPresent(user -> requireRole(user, Role.BRANCH_ADMIN, "branch-admin"));
        if (superAdmin.isPresent() && branchRepository.findByAdminUser(superAdmin.get()).isPresent()) {
            throw conflict("The configured super-admin already administers a branch.");
        }

        List<Branch> candidates = branchRepository.findByRequestRefOrTransactionRefOrRcNumber(
                DEMO_REQUEST_REF, DEMO_TRANSACTION_REF, DEMO_RC_NUMBER);
        if (candidates.size() > 1) {
            throw conflict("The reserved demo branch identifiers belong to different branches.");
        }
        Branch demoBranch = candidates.isEmpty() ? null : candidates.get(0);
        if (demoBranch != null) {
            requireDemoIdentity(demoBranch);
            User owner = demoBranch.getAdminUser();
            if (owner != null && (branchAdmin.isEmpty() || !sameUser(owner, branchAdmin.get()))) {
                throw conflict("The demo branch belongs to another account.");
            }
        }

        if (branchAdmin.isPresent()) {
            Optional<Branch> administeredBranch = branchRepository.findByAdminUser(branchAdmin.get());
            if (administeredBranch.isPresent()) {
                if (demoBranch == null || !Objects.equals(administeredBranch.get().getId(), demoBranch.getId())) {
                    throw conflict("The demo branch-admin already administers another branch.");
                }
            } else if (!passwordEncoder.matches(branchAdminPassword, branchAdmin.get().getPassword())) {
                // Email and role alone cannot establish ownership of an orphaned account.
                throw conflict("An unlinked branch-admin requires its existing password to prove ownership.");
            }
        }

        if (superAdmin.isEmpty()) {
            userRepository.save(newUser(normalizedEmail, adminPassword, Role.SUPER_ADMIN));
        }
        User owner = branchAdmin.orElseGet(() -> userRepository.save(
                newUser(DEMO_BRANCH_EMAIL, branchAdminPassword, Role.BRANCH_ADMIN)));
        if (demoBranch == null) {
            branchRepository.save(newDemoBranch(owner));
        } else if (demoBranch.getAdminUser() == null) {
            demoBranch.setAdminUser(owner);
            branchRepository.save(demoBranch);
        }
    }

    private String requireSeedSetting(String propertyName) {
        String value;
        try {
            value = environment.getProperty(propertyName);
        } catch (IllegalArgumentException exception) {
            // Placeholder exception messages can contain parts of a supplied credential.
            throw new IllegalStateException("Unable to resolve demo seeding configuration for " + propertyName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Demo seeding requires an explicit value for " + propertyName);
        }
        return value;
    }

    private Optional<User> findUser(String email, String identity) {
        try {
            return userRepository.findByEmailIgnoreCase(email);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw conflict("Multiple accounts match the " + identity + " identity.");
        }
    }

    private void requireRole(User user, Role expected, String identity) {
        if (user.getRole() != expected) {
            throw conflict("The existing " + identity + " has an unexpected role; accounts are never promoted.");
        }
    }

    private boolean sameUser(User first, User second) {
        return first.getId() != null && first.getId().equals(second.getId());
    }

    private User newUser(String email, String password, Role role) {
        return User.builder().email(email).password(passwordEncoder.encode(password)).role(role).build();
    }

    private Branch newDemoBranch(User owner) {
        // Synthetic fixtures: these identify no real person, bank, or provider merchant.
        return Branch.builder()
                .adminUser(owner)
                .businessName("Greenfield Demo Campus")
                .businessShortName("GRN-DEMO")
                .billerCode("DEMO-NOT-A-REAL-BILLER")
                .contactEmail(DEMO_BRANCH_EMAIL)
                .contactFirstName("Demo")
                .contactSurname("Administrator")
                .contactPhoneNumber("00000000000")
                .businessAddress("Demonstration Campus, Example City")
                .whatsappNumber("00000000000")
                .settlementAccountNumber("0000000000")
                .settlementBankCode("000")
                .rcNumber(DEMO_RC_NUMBER)
                .tin("DEMO-TIN-001")
                .requestRef(DEMO_REQUEST_REF)
                .transactionRef(DEMO_TRANSACTION_REF)
                .build();
    }

    private void requireDemoIdentity(Branch branch) {
        Branch expected = newDemoBranch(null);
        if (!Objects.equals(branch.getRequestRef(), expected.getRequestRef())
                || !Objects.equals(branch.getTransactionRef(), expected.getTransactionRef())
                || !Objects.equals(branch.getRcNumber(), expected.getRcNumber())
                || !Objects.equals(branch.getBusinessName(), expected.getBusinessName())
                || !Objects.equals(branch.getBusinessShortName(), expected.getBusinessShortName())
                || !Objects.equals(branch.getBillerCode(), expected.getBillerCode())
                || !DEMO_BRANCH_EMAIL.equalsIgnoreCase(branch.getContactEmail())
                || !Objects.equals(branch.getContactFirstName(), expected.getContactFirstName())
                || !Objects.equals(branch.getContactSurname(), expected.getContactSurname())
                || !Objects.equals(branch.getContactPhoneNumber(), expected.getContactPhoneNumber())
                || !Objects.equals(branch.getBusinessAddress(), expected.getBusinessAddress())
                || !Objects.equals(branch.getWhatsappNumber(), expected.getWhatsappNumber())
                || !Objects.equals(branch.getSettlementAccountNumber(), expected.getSettlementAccountNumber())
                || !Objects.equals(branch.getSettlementBankCode(), expected.getSettlementBankCode())
                || !Objects.equals(branch.getTin(), expected.getTin())) {
            throw conflict("The reserved demo branch identifiers conflict with existing branch data.");
        }
    }

    private IllegalStateException conflict(String reason) {
        // Never include entities, supplied credentials or email addresses in conflict messages.
        return new IllegalStateException("Demo seeding conflict: " + reason);
    }
}
