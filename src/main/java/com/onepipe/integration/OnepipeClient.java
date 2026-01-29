package com.onepipe.integration;

import com.onepipe.data.entities.Payment;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeQueryResponse;
import com.onepipe.integration.dto.OnePipeResponse;

public interface OnepipeClient {
    String createMerchant(CreateBranchRequest requestDetails, String transactionRef, String requestRef);
    boolean cancelMandate(Payment payment);
    OnePipeResponse sendInvoice(OnePipeInvoiceRequest request);
    OnePipeResponse queryTransaction(String transactionRef);
}
