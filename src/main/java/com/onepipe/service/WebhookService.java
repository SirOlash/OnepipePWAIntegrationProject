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
        if (payload.getDetails() == null) return;

        String txRef = payload.getDetails().getTransactionRef();
        String status = payload.getDetails().getStatus();

        System.out.println(">>> WEBHOOK RECEIVED for Ref: " + txRef + " Status: " + status);

        Optional<Payment> paymentOpt = paymentRepository.findByTransactionRef(txRef);

        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();

            if ("Successful".equalsIgnoreCase(status)) {
                handleSuccess(payment);
            } else if ("Failed".equalsIgnoreCase(status)) {
                payment.setStatus(PaymentStatus.FAILED);
            }

            paymentRepository.save(payment);
        }
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
