//package com.onepipe.integration;
//
//import com.onepipe.data.entities.Payment;
//import com.onepipe.dtos.request.CreateBranchRequest;
//import com.onepipe.integration.dto.OnePipeInvoiceRequest;
//import com.onepipe.integration.dto.OnePipeResponse;
//import org.springframework.stereotype.Service;
//
//import java.util.UUID;
//
//@Service
//public class MockOnepipeClient implements OnepipeClient{
//    @Override
//    public String createMerchant(CreateBranchRequest requestDetails, String transactionRef) {
//        System.out.println(">>> [MOCK] Creating Merchant: " + requestDetails.getBusinessName());
//        return "MERCH-" + UUID.randomUUID().toString().substring(0, 8);
//    }
//
//    @Override
//    public OnePipeResponse sendInvoice(OnePipeInvoiceRequest request) {
//        System.out.println(">>> [MOCK] Sending Invoice for Ref: " + request.getRequestRef());
//
//        return OnePipeResponse.builder()
//                .virtualAccountNumber("1234567890")
//                .virtualAccountBankName("Mock Bank")
//                .paymentId("MOCK-PAY-" + UUID.randomUUID().toString().substring(0, 8))
//                .requestRef(request.getRequestRef())
//                .transactionRef(request.getTransaction().getTransactionRef())
//                .status("REQUESTED")
//                .message("Mock invoice created successfully")
//                .build();
//    }
//
//}
