package com.onepipe.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OnePipeCancelRequest {
    @JsonProperty("request_ref")
    private String requestRef;

    @JsonProperty("request_type")
    @Builder.Default
    private String requestType = "cancel mandate";

    private Auth auth;
    private Transaction transaction;

    @Data
    @Builder
    public static class Auth {
        @JsonProperty("auth_provider")
        @Builder.Default
        private String authProvider = "PaywithAccount";
        private String secure;
        private String type;
        @JsonProperty("route_mode")
        private String routeMode;
    }

    @Data
    @Builder
    public static class Transaction {
        @JsonProperty("mock_mode")
        @Builder.Default
        private String mockMode = "Inspect";

        @JsonProperty("transaction_ref")
        private String transactionRef;

        @JsonProperty("transaction_desc")
        private String transactionDesc;

        @JsonProperty("transaction_ref_parent")
        @Builder.Default
        private String transactionRefParent = "";

        @Builder.Default
        private BigDecimal amount = BigDecimal.ZERO;

        private Customer customer;
        private Meta meta;
        private Object details;
        private Object options;
    }

    @Data
    @Builder
    public static class Customer {
        @JsonProperty("customer_ref")
        private String customerRef;
        @JsonProperty("mobile_no")
        private String mobileNo;
        private String firstname;
        private String surname;
        private String email;
    }

    @Data
    @Builder
    public static class Meta {
        @JsonProperty("biller_code")
        private String billerCode;

        @JsonProperty("payment_id")
        private String paymentId;

        @Builder.Default
        private Boolean ticket = true;
    }
}
