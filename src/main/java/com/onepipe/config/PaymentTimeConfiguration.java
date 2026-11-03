package com.onepipe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class PaymentTimeConfiguration {
    public static final ZoneId PAYMENT_ZONE = ZoneId.of("Africa/Lagos");

    @Bean
    public Clock paymentClock() {
        return Clock.system(PAYMENT_ZONE);
    }
}
