package com.onepipe.config;

import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.findByEmail("superadmin@greenfield.com").isEmpty()) {
            User superAdmin = User.builder()
                    .email("superadmin@greenfield.com")
                    .password(passwordEncoder.encode("Admin12345"))
                    .role(Role.SUPER_ADMIN)
                    .build();
            userRepository.save(superAdmin);

            System.out.println("✅ SUPER ADMIN CREATED: superadmin@greenfield.com / Admin12345");
        } else {
            System.out.println("ℹ️ Super Admin already exists.");
        }

    }

}
