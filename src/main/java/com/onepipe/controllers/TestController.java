package com.onepipe.controllers;

import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import com.onepipe.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@PreAuthorize("hasRole('SUPER_ADMIN')")
@ConditionalOnProperty(prefix = "onepipe", name = "mode", havingValue = "real")
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
    public ResponseEntity<?> testOnePipeCall(@RequestParam String transactionRef) {
        if (transactionRef == null || transactionRef.isBlank()) {
            throw new InvalidPaymentException("A transaction reference is required for the read-only diagnostic");
        }
        try {
            // Connectivity diagnostics must not create untracked sandbox invoices.
            OnePipeResponse response = onePipeClient.queryTransaction(transactionRef);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("OnePipe diagnostic request failed");
        }
    }
}
