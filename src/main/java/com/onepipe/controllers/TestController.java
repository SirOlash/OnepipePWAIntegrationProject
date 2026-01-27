package com.onepipe.controllers;

import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final OnepipeClient onePipeClient;

    @Value("${onepipe.client-secret}")
    private String clientSecret;

    @GetMapping("/encrypt")
    public ResponseEntity<String> testEncryption() {
        try {
            // Mock data: AccountNumber;BankCode
            String rawData = "1234567890;057";
            String encrypted = EncryptionUtil.encryptTripleDES(rawData, clientSecret);
            return ResponseEntity.ok("Raw: " + rawData + "\nEncrypted: " + encrypted);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/onepipe-connection")
    public ResponseEntity<?> testOnePipeCall() {
        try {
            OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();
            request.setRequestRef(UUID.randomUUID().toString());

            // --- 1. Auth ---
            OnePipeInvoiceRequest.Auth auth = new OnePipeInvoiceRequest.Auth();
            auth.setType("bank.account");
            // We keep the encryption test
            auth.setSecure(EncryptionUtil.encryptTripleDES("1234567890;057", clientSecret));
            request.setAuth(auth);

            // --- 2. Transaction ---
            OnePipeInvoiceRequest.Transaction tx = new OnePipeInvoiceRequest.Transaction();
            tx.setTransactionRef("TEST-" + System.currentTimeMillis());
            tx.setTransactionDesc("Test Installment Connection");
            tx.setAmount(new BigDecimal("10000.00")); // 5000.00 NGN

            // --- 3. Customer (Use dummy data) ---
            OnePipeInvoiceRequest.Customer customer = new OnePipeInvoiceRequest.Customer();
            customer.setCustomerRef("08011111111");
            customer.setFirstname("Test");
            customer.setSurname("User");
            customer.setEmail("test@example.com");
            customer.setMobileNo("08011111111");
            tx.setCustomer(customer);

            // --- 4. Meta (CRITICAL FIXES HERE) ---
            OnePipeInvoiceRequest.Meta meta = new OnePipeInvoiceRequest.Meta();

            // FIX A: Use a valid Biller Code.
            // 000019 is often the OnePipe Demo Biller.
            // If this fails, we must use the one you created manually in their portal.
            meta.setBillerCode("000019");

            meta.setType("instalment");
            meta.setDownPayment(new BigDecimal("1000.00"));
            meta.setRepeatFrequency("daily");

            // FIX B: Use Dynamic Date (Tomorrow)
            // OnePipe expects: yyyy-MM-dd-HH-mm-ss
            String tomorrow = java.time.LocalDateTime.now().plusDays(1)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
            meta.setRepeatStartDate(tomorrow);

            meta.setNumberOfPayments(2);
            tx.setMeta(meta);

            // FIX C: Ensure details is not null (Fixes Jackson error)
            tx.setDetails(new java.util.HashMap<>());

            request.setTransaction(tx);

            System.out.println("Sending Request to OnePipe: " + request); // Debug log

            // Call Client
            OnePipeResponse response = onePipeClient.sendInvoice(request);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace(); // Print full error to console
            return ResponseEntity.internalServerError().body("OnePipe Call Failed: " + e.getMessage());
        }
    }
}
