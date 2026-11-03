package com.onepipe.config;

import com.onepipe.data.enums.ClassGrade;
import com.onepipe.service.PaymentCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class SchoolFeeScheduleTest {
    private final PaymentCalculator calculator = new PaymentCalculator();

    @ParameterizedTest
    @EnumSource(ClassGrade.class)
    void everyClassHasAValidatedOverridableFee(ClassGrade grade) {
        var fees = new SchoolFeeSchedule(new MockEnvironment().withProperty("school.fees." + grade.name(), "234567.89"), calculator);
        assertThat(fees.feeFor(grade)).isEqualTo(new BigDecimal("234567.89"));
        assertThat(fees.validateSubmittedAmount(grade, null, calculator)).isEqualTo(new BigDecimal("234567.89"));
    }

    @Test
    void syntheticScheduleIsDifferentForEachClassAndIsNotTheHistoricalSandboxFee() {
        var fees = new SchoolFeeSchedule(new MockEnvironment(), calculator);
        assertThat(java.util.Arrays.stream(ClassGrade.values()).map(fees::feeFor).toList())
                .containsExactly(new BigDecimal("100000.00"), new BigDecimal("110000.00"), new BigDecimal("120000.00"),
                        new BigDecimal("130000.00"), new BigDecimal("140000.00"), new BigDecimal("150000.00"));
    }

    @Test
    void documentedEnvironmentVariableOverridesTheSampleFee() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-only-env",
                java.util.Map.of("SCHOOL_FEES_JSS1", "543210.12")));
        assertThat(new SchoolFeeSchedule(environment, calculator).feeFor(ClassGrade.JSS1))
                .isEqualTo(new BigDecimal("543210.12"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "0.00", "-1", "1.001"})
    void invalidFeeConfigurationFailsWithoutEchoingTheValue(String value) {
        assertThatThrownBy(() -> new SchoolFeeSchedule(new MockEnvironment().withProperty("school.fees.JSS1", value), calculator))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid positive two-decimal NGN fee configuration: school.fees.JSS1");
    }
}
