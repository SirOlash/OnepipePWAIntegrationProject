package com.onepipe.integration;

import com.onepipe.dtos.request.CreateBranchRequest;

public interface OnepipeClient {
    String createMerchant(CreateBranchRequest requestDetails, String transactionRef);
}
