package com.onepipe.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnePipeResponse {
    private String virtualAccountNumber;
    private String virtualAccountBankName;
    private String paymentId;           // From meta.payment_id
    private String requestRef;          // To track what we sent
    private String transactionRef;      // To track the transaction
    private String status;              // "REQUESTED" or "Successful"
    private String message;
}
