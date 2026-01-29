package com.onepipe.dtos.response;


import com.onepipe.data.enums.PaymentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterStudentResponse {
    private String studentRegId;
    private String studentName;
    private String parentName;
    private String message;

    private PaymentDetails paymentDetails;

    @Data
    @Builder
    public static class PaymentDetails {
        private String onePipePaymentId;
        private BigDecimal amount;
        private BigDecimal downPayment;
        private String bankName;
        private String accountNumber;
        private String accountName;
        private String customerAccountNumber;
        private String paymentType;
        private String expiryDate;
        private String qrCodeImage;
    }

}
