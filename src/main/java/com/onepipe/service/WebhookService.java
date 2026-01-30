package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.dtos.WebhookDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WebhookService {

    private final PaymentRepository paymentRepository;

    public void processWebhook(WebhookDto payload) {
        if (payload.getDetails() == null || payload.getDetails().getMeta() == null) {
            System.out.println(">>> WEBHOOK ERROR: Missing details or meta");
            return;
        }

        String paymentId = payload.getDetails().getMeta().getPaymentId();
        String status = payload.getDetails().getStatus();
        String txRef = payload.getDetails().getTransactionRef(); // OnePipe's internal ref

        System.out.println(">>> WEBHOOK RECEIVED");
        System.out.println("    Payment ID: " + paymentId);
        System.out.println("    OnePipe Tx Ref: " + txRef);
        System.out.println("    Status: " + status);

        // ✅ CHANGED: Find by OnePipe Payment ID
        Optional<Payment> paymentOpt = paymentRepository.findByOnePipePaymentId(paymentId);

        if (paymentOpt.isEmpty()) {
            System.out.println(">>> WEBHOOK ERROR: Payment not found with ID: " + paymentId);
            return;
        }

        Payment payment = paymentOpt.get();
        System.out.println(">>> Found Payment: " + payment.getId() + " for Student: " + payment.getStudent().getFirstName());

        if ("Successful".equalsIgnoreCase(status)) {
            handleSuccess(payment);
        } else if ("Failed".equalsIgnoreCase(status)) {
            payment.setStatus(PaymentStatus.FAILED);
        }

        paymentRepository.save(payment);
        System.out.println(">>> Payment updated successfully!");
    }

    private void handleSuccess(Payment payment) {
        if (payment.getPaymentType() == PaymentType.SINGLE_PAYMENT) {
            payment.setStatus(PaymentStatus.SUCCESSFUL);
        }

        else if (payment.getPaymentType() == PaymentType.INSTALLMENT) {
            int currentCount = (payment.getCompletedPayments() == null) ? 0 : payment.getCompletedPayments();
            int newCount = currentCount + 1;

            payment.setCompletedPayments(newCount);

            int totalExpectedTransactions = payment.getNumberOfPayments() + 1;

            if (newCount >= totalExpectedTransactions) {
                payment.setStatus(PaymentStatus.SUCCESSFUL);
            } else {
                payment.setStatus(PaymentStatus.ACTIVE);
            }
        }

        else if (payment.getPaymentType() == PaymentType.SUBSCRIPTION) {
            payment.setCompletedPayments((payment.getCompletedPayments() == null ? 0 : payment.getCompletedPayments()) + 1);
            payment.setStatus(PaymentStatus.ACTIVE);
        }
    }
}
