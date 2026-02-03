package com.onepipe.dtos.request;

import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import lombok.Data;

@Data
public class SwitchPlanRequest {
    private PaymentType newPaymentType;
    private InstallmentFrequency frequency;
    private Integer numberOfInstallments;
    private String bankAccountNumber;
    private String bankCode;
}
