package com.onepipe.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.transaction.Transaction;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnePipeInvoiceRequest {
    @JsonProperty("request_ref")
    private String requestRef;
    @JsonProperty("request_type")
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Transaction {
        @JsonProperty("mock_mode")
        private String mockMode = "Inspect";

        @JsonProperty("transaction_ref")
        private String transactionRef;

        @JsonProperty("transaction_desc")
        private String transactionDesc;

        @JsonProperty("transaction_ref_parent")
        private String transactionRefParent;

        private BigDecimal amount;
        private Customer customer;
        private Meta meta;
        private Map<String, Object> details;

    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Customer {
        @JsonProperty("customer_ref")
        private String customerRef;
        private String firstname;
        private String surname;
        private String email;
        @JsonProperty("mobile_no")
        private String mobileNo;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        private String type;
        @JsonProperty("expires_in")
        private Integer expiresIn;
        @JsonProperty("skip_messaging")
        private Boolean skipMessaging;
        @JsonProperty("biller_code")
        private String billerCode;

        // Installment-specific
        @JsonProperty("down_payment")
        private BigDecimal downPayment;

        @JsonProperty("repeat_frequency")
        private String repeatFrequency;

        @JsonProperty("repeat_start_date")
        private String repeatStartDate;
        @JsonProperty("number_of_payments")
        private Integer numberOfPayments;

        // Subscription-specific
        @JsonProperty("repeat_end_date")
        private String repeatEndDate;
    }
}

