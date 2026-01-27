package com.onepipe.dtos.response;

import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BranchPaymentDto {
    private Long id;
    private String studentName;
    private String description;
    private BigDecimal amount;
    private BigDecimal remainingAmount;
    private PaymentType type;
    private Integer numberOfPayments;
    private Integer completedPayments;
    private PaymentStatus status;
    private String date;
}
