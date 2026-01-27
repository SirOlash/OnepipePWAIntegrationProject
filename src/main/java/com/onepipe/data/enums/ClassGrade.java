package com.onepipe.data.enums;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public enum ClassGrade {
    JSS1(new BigDecimal("500.00")),
    JSS2(new BigDecimal("500.00")),
    JSS3(new BigDecimal("500.00")),
    SS1(new BigDecimal("500.00")),
    SS2(new BigDecimal("500.00")),
    SS3(new BigDecimal("500.00"));

    private final BigDecimal defaultFee;

    ClassGrade(BigDecimal defaultFee) {
        this.defaultFee = defaultFee;
    }


}
