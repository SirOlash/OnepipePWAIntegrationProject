package com.onepipe.validation;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentInputValidation {
    private final Validator validator;

    public void validate(Object request) {
        var violations = validator.validate(request, Default.class, FinancialInput.class);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    }
}
