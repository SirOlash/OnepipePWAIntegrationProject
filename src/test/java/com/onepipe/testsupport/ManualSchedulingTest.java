package com.onepipe.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ManualSchedulingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingFixture.class);

    @Test
    void manualOverridePreventsBackgroundRegistrationAndPreservesExplicitInvocation() {
        context.withUserConfiguration(ManualScheduling.Configuration.class).run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            ScheduledCounter counter = application.getBean(ScheduledCounter.class);
            assertThat(counter.executions.get()).isZero();
            counter.run();
            assertThat(counter.executions.get()).isEqualTo(1);
        });
    }

    @Test
    void schedulingRemainsEnabledWithoutTheTestOverride() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(application.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                    .hasSize(1);
            assertThat(application.getBean(ScheduledCounter.class).executions.get()).isZero();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingFixture {
        @Bean ScheduledCounter counter() { return new ScheduledCounter(); }
    }

    static class ScheduledCounter {
        final AtomicInteger executions = new AtomicInteger();

        // Registration is asserted without waiting for a timer or using sleeps.
        @Scheduled(initialDelay = 86400000, fixedDelay = 86400000)
        void run() { executions.incrementAndGet(); }
    }
}
