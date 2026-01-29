package com.onepipe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.data.entities.Payment;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.integration.dto.*;
import com.onepipe.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Service
@Primary
@RequiredArgsConstructor
public class RealOnePipeClient implements OnepipeClient {

    @Value("${onepipe.base-url}")
    private String baseUrl;

    @Value("${onepipe.api-key}")
    private String apiKey;

    @Value("${onepipe.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public OnePipeResponse sendInvoice(OnePipeInvoiceRequest request) {
        String url = baseUrl + "/transact";

        // 1. Generate Signature: MD5(request_ref;client_secret)
        String safeRequestRef = request.getRequestRef().trim();
        String safeSecret = clientSecret.trim();

        request.setRequestRef(safeRequestRef);

        String signature = EncryptionUtil.generateSignature(safeRequestRef, safeSecret);

        // 2. Set Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Signature", signature);

        HttpEntity<OnePipeInvoiceRequest> entity = new HttpEntity<>(request, headers);


        try {
            // 4. Send POST
            // OnePipe returns a flexible JSON, so we map it to a generic Map first to be safe
            ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> body = responseEntity.getBody();

            System.out.println(">>> ONEPIPE RAW RESPONSE: " + body);

            if (body == null) {
                throw new RuntimeException("OnePipe returned empty body");
            }

            String status = (String) body.get("status");
            if (!"Successful".equalsIgnoreCase(status) && !"Processing".equalsIgnoreCase(status)) {
                String msg = (String) body.get("message");
                throw new RuntimeException("OnePipe Error: " + msg);
            }

            // 5. Extract Data from Response
            // Navigate: data -> provider_response -> meta -> virtual_account_number
            Map<String, Object> data = (Map<String, Object>) body.get("data");

            if (data == null) {
                throw new RuntimeException("OnePipe Error: Data field is missing");
            }

            Map<String, Object> providerResp = (Map<String, Object>) data.get("provider_response");

            if (providerResp == null) {
                // Sometimes errors are inside 'data' but not in 'provider_response'
                Object innerError = data.get("error");
                throw new RuntimeException("Provider Response is null. Inner Error: " + innerError);
            }

            Map<String, Object> meta = (Map<String, Object>) providerResp.get("meta");

            return OnePipeResponse.builder()
                    .status((String) providerResp.get("status"))
                    .message((String) body.get("message"))

                    .paymentId(String.valueOf(meta.get("payment_id")))

                    .virtualAccountNumber((String) meta.get("virtual_account_number"))
                    .virtualAccountName((String) meta.get("virtual_account_name"))
                    .virtualAccountBankName((String) meta.get("virtual_account_bank_name"))
                    .virtualAccountBankCode((String) meta.get("virtual_account_bank_code"))
                    .virtualAccountExpiryDate((String) meta.get("virtual_account_expiry_date"))
                    .virtualAccountQrCodeUrl((String) meta.get("virtual_account_qr_code_url"))

                    .customerAccountNumber((String) providerResp.get("account_number"))
                    .customerEmail((String) providerResp.get("customer_email"))
                    .createdOn((String) providerResp.get("created_on"))
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("OnePipe API Call Failed: " + e.getMessage());
        }
    }

    @Override
    public String createMerchant(CreateBranchRequest requestDetails, String transactionRef, String requestRef) {
        String url = baseUrl + "/transact";
        OnePipeMerchantRequest request = OnePipeMerchantRequest.builder()
                .requestRef(requestRef)
                .auth(OnePipeMerchantRequest.Auth.builder().build()) // Use defaults
                .transaction(OnePipeMerchantRequest.Transaction.builder()
                        .transactionRef(transactionRef)
                        .transactionDesc("New Branch Creation: " + requestDetails.getBusinessShortName())
                        .customer(OnePipeMerchantRequest.Customer.builder()
                                .customerRef(requestDetails.getContactPhoneNumber())
                                .mobileNo(requestDetails.getContactPhoneNumber())
                                .firstname(requestDetails.getContactFirstName())
                                .surname(requestDetails.getContactSurname())
                                .email(requestDetails.getAdminEmail())
                                .build())
                        .meta(OnePipeMerchantRequest.Meta.builder()
                                .webhookUrl("https://greenfield-backend-lkse.onrender.com/api/webhooks/onepipe") // TODO Update it
                                .whatsappContactName(requestDetails.getContactFirstName())
                                .whatsappContactNo(requestDetails.getWhatsappNumber())
                                .businessShortName(requestDetails.getBusinessShortName())
                                .build())
                        .details(OnePipeMerchantRequest.Details.builder()
                                .businessName(requestDetails.getBusinessName())
                                .rcNumber(requestDetails.getRcNumber())

                                .settlementAccountNo(requestDetails.getSettlementAccountNumber())
                                .settlementBankCode(requestDetails.getSettlementBankCode())
                                .taxId(requestDetails.getTin())
                                .address(requestDetails.getAddress())
                                .notificationPhone(requestDetails.getWhatsappNumber())
                                .notificationEmail(requestDetails.getAdminEmail())
                                .build())
                        .build())
                .build();

        String signature = EncryptionUtil.generateSignature(requestRef, clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Signature", signature);

        HttpEntity<OnePipeMerchantRequest> entity = new HttpEntity<>(request, headers);

        System.out.println(">>> CREATING MERCHANT ON ONEPIPE: " + requestDetails.getBusinessName());

        try {
            ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> body = responseEntity.getBody();

            if (body != null && "Successful".equalsIgnoreCase((String) body.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                Map<String, Object> providerResp = (Map<String, Object>) data.get("provider_response");
                Map<String, Object> meta = (Map<String, Object>) providerResp.get("meta");

                return (String) meta.get("biller_code");
            } else {
                throw new RuntimeException("OnePipe Merchant Creation Failed: " + body.get("message"));
            }

        } catch (HttpClientErrorException e) {
            e.printStackTrace();
//            throw new RuntimeException("API Call Failed: " + e.getMessage());
            throw new ResponseStatusException(
                    e.getStatusCode(),
                    e.getResponseBodyAsString()
            );

        }
    }

    @Override
    public boolean cancelMandate(Payment payment) {
        String url = baseUrl + "/transact";
        String myRequestRef = UUID.randomUUID().toString();
        String myTxnRef = "CNCL-" + System.currentTimeMillis();

        OnePipeCancelRequest request = OnePipeCancelRequest.builder()
                .requestRef(myRequestRef)
                .auth(OnePipeCancelRequest.Auth.builder().build())
                .transaction(OnePipeCancelRequest.Transaction.builder()
                        .transactionRef(myTxnRef)
                        .transactionDesc("Cancel Subscription for " + payment.getStudent().getFirstName())
                        .customer(OnePipeCancelRequest.Customer.builder()
                                .customerRef(payment.getParent().getPhoneNumber())
                                .mobileNo(payment.getParent().getPhoneNumber())
                                .firstname(payment.getParent().getFirstName())
                                .surname(payment.getParent().getSurname())
                                .email(payment.getParent().getUser().getEmail())
                                .build())
                        .meta(OnePipeCancelRequest.Meta.builder()
                                .billerCode(payment.getBranch().getBillerCode())
                                .paymentId(payment.getOnePipePaymentId())
                                .build())
                        .details(new Object())
                        .build())
                .build();

        String signature = EncryptionUtil.generateSignature(myRequestRef, clientSecret);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Signature", signature);

        HttpEntity<OnePipeCancelRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> body = response.getBody();

            if (body != null && "Successful".equalsIgnoreCase((String) body.get("status"))) {
                return true;
            }
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

        @Override
        public OnePipeResponse queryTransaction(String transactionRef) {
            String url = baseUrl + "/transact/query";
            String requestRef = UUID.randomUUID().toString();

            OnePipeQueryRequest request = OnePipeQueryRequest.builder()
                    .requestRef(requestRef)
                    .transaction(OnePipeQueryRequest.Transaction.builder()
                            .transactionRef(transactionRef)
                            .build())
                    .build();

            // Signature logic
            String signature = EncryptionUtil.generateSignature(requestRef, clientSecret);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("Signature", signature);

            HttpEntity<OnePipeQueryRequest> entity = new HttpEntity<>(request, headers);

            try {
                ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, entity, Map.class);
                Map<String, Object> body = responseEntity.getBody();

                // Check top level status
                if (body == null || !"Successful".equalsIgnoreCase((String) body.get("status"))) {
                    return OnePipeResponse.builder().status("Failed").message("Query Request Failed").build();
                }

                // Extract Data
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                Map<String, Object> providerResp = (Map<String, Object>) data.get("provider_response");

                // Return a simplified response with just the Status and Ref
                return OnePipeResponse.builder()
                        .status((String) providerResp.get("status")) // "successful"
                        .transactionRef(transactionRef)
                        .message((String) body.get("message"))
                        .build();

            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Query Call Failed: " + e.getMessage());
            }
        }
}
