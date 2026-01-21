package com.onepipe.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class WebhookDto {
    private String request_ref;
    private String request_type;

    @JsonProperty("details")
    private Details details;

    @Data
    public static class Details {
        @JsonProperty("status")
        private String status;

        @JsonProperty("transaction_ref")
        private String transactionRef;

        @JsonProperty("amount")
        private String amount;

        @JsonProperty("provider")
        private String provider;

        @JsonProperty("meta")
        private Meta meta;
    }

    @Data
    public static class Meta {
        private String payment_id;
    }
}
