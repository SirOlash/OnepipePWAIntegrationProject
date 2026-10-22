package com.onepipe.dtos.response;

import com.onepipe.data.enums.PaymentStatus;
import java.math.BigDecimal;

/** Every result from the currently enabled processor is simulated, never real money movement. */
public record PaymentCollectionResult(String eventId, String transactionRef, String collectionSlot,
        BigDecimal confirmedAmount, String currency, String source, PaymentStatus status,
        BigDecimal remainingAmount, int completedPayments, boolean duplicate, boolean simulated) { }
