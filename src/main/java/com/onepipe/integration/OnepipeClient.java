package com.onepipe.integration;

import com.onepipe.data.entities.Payment;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;

public interface OnepipeClient {
    String createMerchant(CreateBranchRequest requestDetails, String transactionRef, String requestRef);
    boolean cancelMandate(Payment payment);
    OnePipeResponse sendInvoice(OnePipeInvoiceRequest request);

    /** Allows an adapter to prepare provider-specific authorization without coupling services to secrets. */
    default OnePipeResponse sendInvoice(OnePipeInvoiceRequest request, Payment payment) {
        return sendInvoice(request);
    }

    OnePipeResponse queryTransaction(String transactionRef);
}
