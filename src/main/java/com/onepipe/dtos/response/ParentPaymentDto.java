package com.onepipe.dtos.response;

import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ParentPaymentDto {
    private String onePipePaymentId;
    private String description;
    private BigDecimal amount;
    private BigDecimal downPayment;
    private String customerAccountNumber;
    private PaymentStatus status;
    private PaymentType paymentType;
    private String date;

    private BigDecimal remainingAmount;
    private Integer numberOfPayments;
    private Integer completedPayments;

    private String virtualAccountNumber;
    private String virtualAccountBankName;
    private String virtualAccountName;
    private String virtualAccountExpiryDate;

    private String qrCodeUrl;
}
