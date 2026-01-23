package com.onepipe.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnePipeResponse {
    private String virtualAccountNumber;
    private String virtualAccountBankName;
    private String virtualAccountName;
    private String virtualAccountBankCode;
    private String virtualAccountExpiryDate;

    private String customerAccountNumber; // for installment and subscription, the customer must transfer from this account
    private String customerEmail;

    private String paymentId;           // From meta.payment_id
    private String subscriptionId;
    private String requestRef;
    private String transactionRef;
    private String reference;

    private String status;
    private String message;
    private String createdOn;
}
