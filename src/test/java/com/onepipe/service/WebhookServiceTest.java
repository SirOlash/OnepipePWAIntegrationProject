package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.dtos.WebhookDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private WebhookService webhookService;

    @Test
    void testProcessWebhook_SuccessfulPayment() {
        // 1. SETUP
        String myTransactionRef = "TXN-12345";

        // Mock a Pending Payment existing in DB
        Payment existingPayment = new Payment();
        existingPayment.setTransactionRef(myTransactionRef);
        existingPayment.setStatus(PaymentStatus.PENDING);

        when(paymentRepository.findByTransactionRef(myTransactionRef)).thenReturn(Optional.of(existingPayment));

        // Create the Webhook Payload coming from OnePipe
        WebhookDto payload = new WebhookDto();
        WebhookDto.Details details = new WebhookDto.Details();
        details.setTransactionRef(myTransactionRef);
        details.setStatus("Successful");
        payload.setDetails(details);

        // 2. EXECUTE
        webhookService.processWebhook(payload);

        // 3. VERIFY
        // Check if DB save was called
        verify(paymentRepository, times(1)).save(existingPayment);

        // Check if Status changed to SUCCESSFUL
        Assertions.assertEquals(PaymentStatus.PENDING, existingPayment.getStatus());
        System.out.println("✅ Webhook Verification: Status updated to SUCCESSFUL");
    }
}