package com.onepipe.dtos.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Only arrangement identity and a slot are accepted; financial values are determined by the server. */
@Data
public class MockPaymentSimulationRequest {
    @NotBlank @Size(max = 100)
    private String transactionRef;

    @NotBlank @Size(max = 50)
    private String collectionSlot;

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new InvalidPaymentException("Unsupported simulation request fields");
    }
}
