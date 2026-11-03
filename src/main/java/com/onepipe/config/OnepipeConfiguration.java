package com.onepipe.config;

import com.onepipe.integration.OnepipeClient;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class OnepipeConfiguration {

    @Bean
    static BeanFactoryPostProcessor validateOnepipeProvider(Environment environment) {
        return beanFactory -> {
            String mode = environment.getProperty("onepipe.mode", "mock");
            if (!"mock".equalsIgnoreCase(mode) && !"real".equalsIgnoreCase(mode)) {
                throw new IllegalStateException("Unsupported onepipe.mode; expected 'mock' or 'real'.");
            }

            String[] providers = beanFactory.getBeanNamesForType(OnepipeClient.class, true, false);
            if (providers.length != 1) {
                throw new IllegalStateException("Exactly one OnepipeClient provider must be selected by onepipe.mode.");
            }
        };
    }
}
