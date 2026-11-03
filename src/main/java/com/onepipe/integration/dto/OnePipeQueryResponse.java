package com.onepipe.integration.dto;


import com.onepipe.data.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnePipeQueryResponse {
    private String status;
    private String requestRef;
    private String transactionRef;
    private String onePipePaymentId;
    private String message;
    private BigDecimal confirmedAmount;
    private BigDecimal remainingAmount;
    private String balanceBasis;
    private String invoiceWindowStatus;
    private String providerAvailability;
    private String providerStatus;
    private Boolean providerStateConsistent;
    private Boolean localStateConsistent;
}
