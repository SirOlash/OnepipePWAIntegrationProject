package com.onepipe.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PaymentJsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictNumericInput() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
