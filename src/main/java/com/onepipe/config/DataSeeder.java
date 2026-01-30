package com.onepipe.config;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.super-admin.email}")
    private String adminEmail;

    @Value("${app.super-admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) throws Exception {
        seedSuperAdmin();
        seedAbujaBranch();
    }

    private void seedSuperAdmin() {
        if (userRepository.findByEmailIgnoreCase(adminEmail).isEmpty()) {
            User superAdmin = User.builder()
                    .email(adminEmail.toLowerCase())
                    .password(passwordEncoder.encode(adminPassword))
                    .role(Role.SUPER_ADMIN)
                    .build();
            userRepository.save(superAdmin);

            System.out.println("✅ SUPER ADMIN CREATED: " + adminEmail);
        } else {
            System.out.println("ℹ️ Super Admin already exists.");
        }

    }

    private void seedAbujaBranch() {
        String branchEmail = "olasupoemmanuel30@gmail.com";
        String billerCode = "000740";
        String branchName = "Greenfield Abuja Campus";

        if (branchRepository.findByAdminUser_EmailIgnoreCase(branchEmail).isEmpty() && userRepository.findByEmailIgnoreCase(branchEmail).isEmpty()) {

            // 1. Create the Admin User
            User branchAdmin = User.builder()
                    .email(branchEmail)
                    .password(passwordEncoder.encode("Greenabuja12345"))
                    .role(Role.BRANCH_ADMIN)
                    .build();
            branchAdmin = userRepository.save(branchAdmin);

            // 2. Create the Branch linked to this User
            // We use dummy refs because this branch already exists on OnePipe side
            Branch branch = Branch.builder()
                    .adminUser(branchAdmin)
                    .businessName(branchName)
                    .businessShortName("GRN-ABJ")
                    .billerCode(billerCode)
                    .contactEmail(branchEmail)
                    .contactFirstName("Adebola")
                    .contactSurname("Oluwadare")
                    .contactPhoneNumber("2347066905761")
                    .businessAddress("Municipal Area Abuja")
                    .whatsappNumber("07066905761")
                    .settlementAccountNumber("7790058016")
                    .settlementBankCode("214")

                    // Fill required dummy data for DB constraints
                    .rcNumber("0003")
                    .tin("12345")
                    .requestRef("HARDCODED_PWA_REF")
                    .transactionRef("HARDCODED_PWA_TXN")
                    .build();

            branchRepository.save(branch);
            System.out.println("✅ PWA BRANCH SEEDED: " + branchName);
        } else {
            System.out.println("ℹ️ PWA Branch already exists.");
        }
    }

}
