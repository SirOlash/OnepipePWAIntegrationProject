package com.onepipe.service;

import com.onepipe.dtos.WebhookDto;
import org.springframework.stereotype.Service;

@Service
public class WebhookService {

    /**
     * Keep direct callers fail closed as well as the public HTTP route.
     * Future provider events require an authoritative verification specification
     * before they may enter the trusted collection processor.
     */
    public void processWebhook(WebhookDto payload) {
        throw new UnsupportedOperationException("Payment webhook verification is unavailable");
    }
}
