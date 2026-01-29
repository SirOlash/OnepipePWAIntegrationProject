package com.onepipe.integration.dto;


import com.onepipe.data.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
