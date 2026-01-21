package com.onepipe.controllers;

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

    @PostMapping("/onepipe")
    public ResponseEntity<Void> handleOnePipeWebhook(
            @RequestBody WebhookDto payload,
            @RequestHeader(value = "Signature", required = false) String signature
    ) {
        webhookService.processWebhook(payload);

        return ResponseEntity.ok().build();
    }
}
