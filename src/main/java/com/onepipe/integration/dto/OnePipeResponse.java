package com.onepipe.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnePipeResponse {
    // Offline mock diagnostics in provider units, not new OnePipe request fields.
    private BigDecimal amountInKobo;
    private BigDecimal downPaymentInKobo;
    private String repeatFrequency;
    private String repeatStartDate;
    private String repeatEndDate;
    private Integer numberOfPayments;
    private String virtualAccountNumber;
    private String virtualAccountBankName;
    private String virtualAccountName;
    private String virtualAccountBankCode;
    private String virtualAccountExpiryDate;

    private String customerAccountNumber; // for installment and subscription, the customer must transfer from this account
    private String customerEmail;
    private String transactionRef;

    private String paymentId;
//    private String reference;

    private String status;
    private String message;
    private String createdOn;
    private String virtualAccountQrCodeUrl;
}
