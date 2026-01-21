package com.onepipe.dtos.request;

import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRequest {
    private Long studentId;
    private PaymentCategory category;
    private BigDecimal amount;
    private PaymentType paymentType;
    private String description;

}
