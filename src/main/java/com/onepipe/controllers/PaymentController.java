package com.onepipe.controllers;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.dtos.request.PaymentRequest;
import com.onepipe.dtos.request.SwitchPlanRequest;
import com.onepipe.dtos.response.BranchPaymentDto;
import com.onepipe.dtos.response.ParentPaymentDto;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.integration.dto.OnePipeQueryResponse;
import com.onepipe.service.PaymentService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
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
            @Valid @RequestBody PaymentRequest dto) {

        RegisterStudentResponse response = paymentService.triggerNewPayment(dto);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancelSubscription(@PathVariable String id) {
        paymentService.cancelSubscription(id);
        return ResponseEntity.ok("Subscription cancelled successfully");
    }

    @GetMapping(params = {"branchId", "!studentId"})
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
    public ResponseEntity<OnePipeQueryResponse> queryPaymentStatus(@PathVariable String id) {
        return ResponseEntity.ok(paymentService.queryAndFixPaymentStatus(id));
    }

    @PutMapping("/students/{studentId}/switch-plan")
    public ResponseEntity<String> updatePaymentPlan(
            @PathVariable Long studentId,
            @Valid @RequestBody SwitchPlanRequest request
    ) {
        paymentService.updatePaymentPlan(studentId, request);
        return ResponseEntity.ok("Payment Plan updated successfully.");
    }
}
