package com.onepipe.controllers;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.dtos.request.PaymentRequest;
import com.onepipe.dtos.response.BranchPaymentDto;
import com.onepipe.dtos.response.ParentPaymentDto;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/new")
    public ResponseEntity<RegisterStudentResponse> triggerNewPayment(
            @RequestBody PaymentRequest dto) {

        RegisterStudentResponse response = paymentService.triggerNewPayment(
                dto.getStudentId(),
                dto.getCategory(),
                dto.getAmount(),
                dto.getPaymentType(),
                dto.getDescription()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    // Simple authorization: Any logged in user can try, but logic might restrict ownership later
    // For MVP, we assume if they have the ID, they can cancel it.
    public ResponseEntity<String> cancelSubscription(@PathVariable Long id) {
        paymentService.cancelSubscription(id);
        return ResponseEntity.ok("Subscription cancelled successfully");
    }

    @GetMapping
    public ResponseEntity<List<BranchPaymentDto>> getPaymentsByBranch(
            @RequestParam Long branchId
    ) {
        return ResponseEntity.ok(paymentService.getPaymentsByBranch(branchId));
    }

    @GetMapping(params = "studentId")
    public ResponseEntity<List<ParentPaymentDto>> getStudentPayments(
            @RequestParam Long studentId
    ) {
        return ResponseEntity.ok(paymentService.getPaymentsForChild(studentId));
    }

    @PostMapping("/{id}/query")
    public ResponseEntity<Payment> queryPaymentStatus(@PathVariable Long id) {
        Payment updatedPayment = paymentService.queryAndFixPaymentStatus(id);
        return ResponseEntity.ok(updatedPayment);
    }
}
