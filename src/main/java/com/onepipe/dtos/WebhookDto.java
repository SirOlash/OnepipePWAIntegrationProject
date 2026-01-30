package com.onepipe.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookDto {
    @JsonProperty("request_ref")
    private String requestRef;

    @JsonProperty("request_type")
    private String requestType;

    @JsonProperty("details")
    private Details details;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Details {
        @JsonProperty("status")
        private String status;

        @JsonProperty("transaction_ref")
        private String transactionRef;

        @JsonProperty("amount")
        private String amount;

        @JsonProperty("provider")
        private String provider;

        @JsonProperty("customer_ref")
        private String customerRef;

        @JsonProperty("meta")
        private Meta meta;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        @JsonProperty("payment_id")
        private String paymentId;

        @JsonProperty("biller_code")
        private String billerCode;

        @JsonProperty("payment_option")
        private String paymentOption;

        @JsonProperty("note")
        private String note;
    }
}
