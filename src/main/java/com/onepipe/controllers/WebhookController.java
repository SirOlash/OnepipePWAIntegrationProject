package com.onepipe.controllers;

import com.onepipe.exceptions.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    /**
     * The historical provider environment has no available verification contract.
     * Do not parse, log or acknowledge any submitted financial event as accepted.
     */
    @PostMapping("/onepipe")
    public ResponseEntity<ErrorResponse> handleOnePipeWebhook(HttpServletRequest request) {
        return ResponseEntity.status(503).body(new ErrorResponse(
                "WEBHOOK_VERIFICATION_UNAVAILABLE",
                "Payment webhook verification is unavailable",
                503,
                request.getRequestURI()));
    }
}
