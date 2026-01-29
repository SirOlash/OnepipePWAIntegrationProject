package com.onepipe.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.transaction.Transaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OnePipeMerchantRequest {
    @JsonProperty("request_ref")
    private String requestRef;

    @JsonProperty("request_type")
    @Builder.Default
    private String requestType = "create merchant";

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
        private String mockMode = "Live";

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
        private Details details;
        private Object options;
    }

    @Data
    @Builder
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
    @Builder
    public static class Meta {
        @Builder.Default
        private String beta = "enabled";
        @JsonProperty("biller_sector")
        @Builder.Default
        private String billerSector = "Aggregattor";
        @JsonProperty("simple_payment")
        @Builder.Default
        private String simplePayment = "enabled";

        @JsonProperty("webhook_url")
        private String webhookUrl;

        @JsonProperty("whatsapp_contact_name")
        private String whatsappContactName;
        @JsonProperty("whatsapp_contact_no")
        private String whatsappContactNo;
        @JsonProperty("business_short_name")
        private String businessShortName;
    }

    @Data
    @Builder
    public static class Details {
        @JsonProperty("business_name")
        private String businessName;
        @JsonProperty("rc_number")
        private String rcNumber;

        @JsonProperty("settlement_account_no")
        private String settlementAccountNo;
        @JsonProperty("settlement_bank_code")
        private String settlementBankCode;

        // tin in your entity maps to tax_id here
        @JsonProperty("tin")
        private String taxId;

        @JsonProperty("address")
        private String address;

        @JsonProperty("notification_phone_number")
        private String notificationPhone;
        @JsonProperty("notification_email")
        private String notificationEmail;
    }
}
