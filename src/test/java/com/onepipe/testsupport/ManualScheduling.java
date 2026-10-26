package com.onepipe.testsupport;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps database integration tests in control of scheduled work. Tests invoke the
 * expiry entry point explicitly instead of racing a timer against fixture cleanup.
 * This changes only test contexts; application scheduling remains enabled.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(ManualScheduling.Configuration.class)
public @interface ManualScheduling {

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        static BeanFactoryPostProcessor removeAutomaticScheduling() {
            // @EnableScheduling on the application explicitly registers this
            // processor, independently of Boot's task-scheduler auto-configuration.
            return beanFactory -> {
                BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
                String processor = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if (registry.containsBeanDefinition(processor)) {
                    registry.removeBeanDefinition(processor);
                }
            };
        }
    }
}
