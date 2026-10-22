package com.onepipe.dtos.request;

import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import lombok.Data;
import com.onepipe.validation.FinancialInput;
import jakarta.validation.constraints.*;

@Data
public class SwitchPlanRequest {
    @NotNull(groups = FinancialInput.class)
    private PaymentType newPaymentType;
    private InstallmentFrequency frequency;
    @Positive(groups = FinancialInput.class)
    @Max(value = com.onepipe.service.PaymentCalculator.MAX_INSTALLMENTS, groups = FinancialInput.class)
    private Integer numberOfInstallments;
    @Size(max = 255, groups = FinancialInput.class)
    private String bankAccountNumber;
    @Size(max = 255, groups = FinancialInput.class)
    private String bankCode;
}
