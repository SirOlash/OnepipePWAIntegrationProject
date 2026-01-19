package com.onepipe.integration;

import com.onepipe.dtos.request.CreateBranchRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MockOnepipeClient implements OnepipeClient{
    @Override
    public String createMerchant(CreateBranchRequest requestDetails, String transactionRef) {
        System.out.println("-------------------------------------------------");
        System.out.println("MOCKING ONEPIPE API CALL");
        System.out.println("Request: Create Merchant for " + requestDetails.getBusinessName());
        System.out.println("Txn Ref: " + transactionRef);
        System.out.println("-------------------------------------------------");

        return "MERCH-MOCK-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
