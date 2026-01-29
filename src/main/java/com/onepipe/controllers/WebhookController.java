package com.onepipe.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.dtos.WebhookDto;
import com.onepipe.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;
    private final ObjectMapper objectMapper; // Spring automatically injects this

    @PostMapping("/onepipe")
    public ResponseEntity<Void> handleOnePipeWebhook(
            @RequestBody String rawPayload, // <--- CHANGE 1: Accept Raw String
            @RequestHeader(value = "Signature", required = false) String signature
    ) {
        // --- LOGGING ---
        System.out.println("\n========== WEBHOOK RECEIVED ==========");
        System.out.println("Signature: " + signature);
        System.out.println("Payload: " + rawPayload);
        System.out.println("======================================\n");

        try {
            // --- CHANGE 2: Manually Convert to DTO ---
            // This way, if conversion fails, we still see the log above!
            WebhookDto dto = objectMapper.readValue(rawPayload, WebhookDto.class);

            webhookService.processWebhook(dto);

        } catch (Exception e) {
            System.err.println("!!! WEBHOOK ERROR !!!");
            System.err.println("Could not parse JSON: " + e.getMessage());
            e.printStackTrace();
            // We still return 200 OK to OnePipe so they stop retrying (optional strategy)
            // or return 400 if you want them to retry.
            // For debugging, 200 is often safer to prevent flood.
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.ok().build();
    }
}
