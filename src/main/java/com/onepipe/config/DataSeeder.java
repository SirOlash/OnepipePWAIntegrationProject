package com.onepipe.config;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@ConditionalOnProperty(name = "app.demo.seed.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final String DEMO_BRANCH_EMAIL = "branch-admin@example.invalid";

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.super-admin.email}")
    private String adminEmail;

    @Value("${app.super-admin.password}")
    private String adminPassword;

    @Value("${app.demo.branch-admin.password}")
    private String branchAdminPassword;

    @Override
    public void run(String... args) {
        requireSeedSetting(adminEmail, "app.super-admin.email");
        requireSeedSetting(adminPassword, "app.super-admin.password");
        requireSeedSetting(branchAdminPassword, "app.demo.branch-admin.password");
        adminEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
        if (DEMO_BRANCH_EMAIL.equalsIgnoreCase(adminEmail)) {
            throw new IllegalStateException("Demo super-admin and branch-admin emails must be different");
        }

        seedSuperAdmin();
        seedDemoBranch();
    }

    private void requireSeedSetting(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Demo seeding requires an explicit value for " + propertyName);
        }
    }

    private void seedSuperAdmin() {
        if (userRepository.findByEmailIgnoreCase(adminEmail).isEmpty()) {
            User superAdmin = User.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode(adminPassword))
                    .role(Role.SUPER_ADMIN)
                    .build();
            userRepository.save(superAdmin);
            System.out.println("Demo super-admin created.");
        } else {
            System.out.println("Super-admin already exists; credentials preserved.");
        }
    }

    private void seedDemoBranch() {
        if (branchRepository.findByAdminUser_EmailIgnoreCase(DEMO_BRANCH_EMAIL).isEmpty()
                && userRepository.findByEmailIgnoreCase(DEMO_BRANCH_EMAIL).isEmpty()) {
            User branchAdmin = User.builder()
                    .email(DEMO_BRANCH_EMAIL)
                    .password(passwordEncoder.encode(branchAdminPassword))
                    .role(Role.BRANCH_ADMIN)
                    .build();
            branchAdmin = userRepository.save(branchAdmin);

            // Synthetic local fixtures: these identify no real person, bank, or provider merchant.
            Branch branch = Branch.builder()
                    .adminUser(branchAdmin)
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
                    .rcNumber("DEMO-RC-001")
                    .tin("DEMO-TIN-001")
                    .requestRef("DEMO-BRANCH-REF")
                    .transactionRef("DEMO-BRANCH-TXN")
                    .build();

            branchRepository.save(branch);
            System.out.println("Synthetic demo branch created.");
        } else {
            System.out.println("Demo branch or administrator already exists; credentials preserved.");
        }
    }
}
