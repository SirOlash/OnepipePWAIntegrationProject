package com.onepipe.integration.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OnePipeQueryRequest {
    @JsonProperty("request_ref")
    private String requestRef;

    @JsonProperty("request_type")
    @Builder.Default
    private String requestType = "send invoice";

    private Transaction transaction;

    @Data
    @Builder
    public static class Transaction {
        @JsonProperty("transaction_ref")
        private String transactionRef;
    }
}

