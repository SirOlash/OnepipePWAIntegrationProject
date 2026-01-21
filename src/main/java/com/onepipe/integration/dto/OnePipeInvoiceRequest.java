package com.onepipe.integration.dto;

import jakarta.transaction.Transaction;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OnePipeInvoiceRequest {
    private String requestRef;
    private String requestType = "send invoice";
    private Auth auth;
    private Transaction transaction;

    @Data
    public static class Auth {
        private String type;
        private String secure;
        private String authProvider = "PaywithAccount";
    }

    @Data
    public static class Transaction {
        private String mockMode = "inspect";
        private String transactionRef;
        private String transactionDesc;
        private String transactionRefParent;
        private BigDecimal amount;
        private Customer customer;
        private Meta meta;
        private Object details = new Object();
    }

    @Data
    public static class Customer {
        private String customerRef;
        private String firstname;
        private String surname;
        private String email;
        private String mobileNo;
    }

    @Data
    public static class Meta {
        private String type;
        private Integer expiresIn;
        private Boolean skipMessaging;
        private String billerCode;

        // Installment-specific
        private BigDecimal downPayment;
        private String repeatFrequency;
        private String repeatStartDate;
        private Integer numberOfPayments;

        // Subscription-specific
        private String repeatEndDate;
    }
}

