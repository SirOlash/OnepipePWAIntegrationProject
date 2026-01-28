package com.onepipe.config;

import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
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
    private final PasswordEncoder passwordEncoder;

    @Value("${app.super-admin.email}")
    private String adminEmail;

    @Value("${app.super-admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.findByEmail(adminEmail).isEmpty()) {
            User superAdmin = User.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode(adminPassword))
                    .role(Role.SUPER_ADMIN)
                    .build();
            userRepository.save(superAdmin);

            System.out.println("✅ SUPER ADMIN CREATED: " + adminEmail);
        } else {
            System.out.println("ℹ️ Super Admin already exists.");
        }

    }

}
