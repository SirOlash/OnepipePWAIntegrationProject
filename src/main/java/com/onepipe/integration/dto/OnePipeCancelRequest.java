package com.onepipe.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnePipeCancelRequest {
    @JsonProperty("request_ref")
    private String requestRef;

    @JsonProperty("request_type")
    @Builder.Default
    private String requestType = "Cancel mandate";

    private Auth auth;
    private Transaction transaction;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Auth {
        @JsonProperty("auth_provider")
        @Builder.Default
        private String authProvider = "PaywithAccount";
        @Builder.Default
        private String secure = null;
        @Builder.Default
        private String type = null;
//        @JsonProperty("route_mode")
//        private String routeMode;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Transaction {
        @JsonProperty("mock_mode")
        @Builder.Default
        private String mockMode = "Live";

        @JsonProperty("transaction_ref")
        private String transactionRef;

        @JsonProperty("transaction_desc")
        private String transactionDesc;

        @JsonProperty("transaction_ref_parent")
        @Builder.Default
        private String transactionRefParent = null;

        @Builder.Default
        private BigDecimal amount = BigDecimal.ZERO;

        private Customer customer;
        private Meta meta;
        private Map<String, Object> details;
        private Map<String, Object> options;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        @JsonProperty("biller_code")
        private String billerCode;

        @JsonProperty("payment_id")
        private String paymentId;

//        @Builder.Default
//        private Boolean ticket = true;
    }
}
