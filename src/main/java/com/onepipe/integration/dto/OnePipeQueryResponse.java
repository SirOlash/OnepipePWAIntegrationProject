package com.onepipe.integration.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnePipeQueryResponse {
    private String status;
    private String transactionRef;
    private String message;
}
