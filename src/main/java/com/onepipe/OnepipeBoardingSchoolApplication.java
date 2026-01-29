package com.onepipe;

import com.onepipe.data.repositories.StudentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.onepipe.data.repositories")
@EntityScan(basePackages = "com.onepipe.data.entities")
@EnableScheduling
public class OnepipeBoardingSchoolApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnepipeBoardingSchoolApplication.class, args);
    }

//    @Bean
//    CommandLineRunner runner(StudentRepository studentRepository) {
//        return args -> {
//            System.out.println("✅ Onepipe Boarding School application started.");
//            long count = studentRepository.count();
//            System.out.println("Current student count (H2): " + count);
//            System.out.println("H2 console available at: http://localhost:8080/h2-console");
//        };
//    }
}