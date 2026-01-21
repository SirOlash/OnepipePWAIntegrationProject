package com.onepipe.controllers;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.dtos.request.PaymentRequest;
import com.onepipe.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/new")
    public ResponseEntity<Payment> triggerNewPayment(
            @RequestBody PaymentRequest dto) {

        Payment payment = paymentService.triggerNewPayment(
                dto.getStudentId(),
                dto.getCategory(),
                dto.getAmount(),
                dto.getPaymentType(),
                dto.getDescription()
        );
        return ResponseEntity.ok(payment);
    }
}
