package com.onepipe.dtos.request;

import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import lombok.Data;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.validation.FinancialInput;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

@Data
public class PaymentRequest {
    @NotNull @Positive
    private Long studentId;
    @NotNull(groups = FinancialInput.class)
    private PaymentCategory category;
    @Positive(groups = FinancialInput.class)
    @Digits(integer = 36, fraction = 2, groups = FinancialInput.class)
    private BigDecimal amount;
    @NotNull(groups = FinancialInput.class)
    private PaymentType paymentType;
    @Size(max = 255, groups = FinancialInput.class)
    private String description;
    private InstallmentFrequency frequency;
    @Positive(groups = FinancialInput.class)
    @Max(value = com.onepipe.service.PaymentCalculator.MAX_INSTALLMENTS, groups = FinancialInput.class)
    private Integer numberOfInstallments;

}
