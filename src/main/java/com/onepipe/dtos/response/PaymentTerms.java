package com.onepipe.dtos.response;

import com.onepipe.data.enums.InstallmentFrequency;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Planned obligations, separate from payment confirmation and legacy collection counters. */
public record PaymentTerms(String currency, BigDecimal amountPerCycle, List<BigDecimal> installmentAmounts,
                           InstallmentFrequency frequency, LocalDateTime firstBillingDate,
                           LocalDateTime endDate, BigDecimal remainingAmount, String balanceBasis) { }
