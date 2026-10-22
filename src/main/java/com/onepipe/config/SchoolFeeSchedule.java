package com.onepipe.config;

import com.onepipe.data.enums.ClassGrade;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import com.onepipe.service.PaymentCalculator;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/** A single server fee schedule; existing invoices retain their stored monetary snapshot. */
@Component
public class SchoolFeeSchedule {
    private final Map<ClassGrade, BigDecimal> fees = new EnumMap<>(ClassGrade.class);

    public SchoolFeeSchedule(Environment environment, PaymentCalculator calculator) {
        Properties samples = new Properties();
        try (InputStream stream = new ClassPathResource("school-fees.properties").getInputStream()) {
            samples.load(stream);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load school fee schedule", ex);
        }
        for (ClassGrade grade : ClassGrade.values()) {
            String key = "school.fees." + grade.name();
            try {
                fees.put(grade, calculator.money(new BigDecimal(environment.getProperty(key, samples.getProperty(key)))));
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Invalid positive two-decimal NGN fee configuration: " + key);
            }
        }
    }

    public BigDecimal feeFor(ClassGrade grade) {
        if (grade == null) throw new InvalidPaymentException("Student class is required");
        return fees.get(grade);
    }

    public BigDecimal validateSubmittedAmount(ClassGrade grade, BigDecimal submitted, PaymentCalculator calculator) {
        BigDecimal official = feeFor(grade);
        if (submitted != null && calculator.money(submitted).compareTo(official) != 0) {
            throw new InvalidPaymentException("School fee amount must match the configured class fee");
        }
        return official;
    }
}
