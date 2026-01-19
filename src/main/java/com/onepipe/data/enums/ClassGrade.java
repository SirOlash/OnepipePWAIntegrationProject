package com.onepipe.data.enums;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public enum ClassGrade {
    JSS1(new BigDecimal("100000.00")),
    JSS2(new BigDecimal("200000.00")),
    JSS3(new BigDecimal("300000.00")),
    SS1(new BigDecimal("400000.00")),
    SS2(new BigDecimal("500000.00")),
    SS3(new BigDecimal("600000.00"));

    private final BigDecimal defaultFee;

    ClassGrade(BigDecimal defaultFee) {
        this.defaultFee = defaultFee;
    }


}
