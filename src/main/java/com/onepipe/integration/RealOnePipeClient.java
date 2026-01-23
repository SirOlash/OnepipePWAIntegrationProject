package com.onepipe.integration;

import com.onepipe.data.entities.Payment;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.integration.dto.OnePipeCancelRequest;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeMerchantRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
        String signature = EncryptionUtil.generateSignature(request.getRequestRef(), clientSecret);

        // 2. Set Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Signature", signature);

        HttpEntity<OnePipeInvoiceRequest> entity = new HttpEntity<>(request, headers);

        System.out.println(">>> CALLING ONEPIPE (Real): " + url);

        try {
            // 4. Send POST
            // OnePipe returns a flexible JSON, so we map it to a generic Map first to be safe
            ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> body = responseEntity.getBody();

            // 5. Extract Data from Response
            // Navigate: data -> provider_response -> meta -> virtual_account_number
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            Map<String, Object> providerResp = (Map<String, Object>) data.get("provider_response");
            Map<String, Object> meta = (Map<String, Object>) providerResp.get("meta");

            return OnePipeResponse.builder()
                    .status((String) providerResp.get("status"))
                    .message((String) body.get("message"))

                    .paymentId(String.valueOf(meta.get("payment_id")))
                    .requestRef(request.getRequestRef())
                    .transactionRef(request.getTransaction().getTransactionRef())
                    .reference((String) providerResp.get("reference"))
                    .subscriptionId((String) meta.get("activation_url"))

                    .virtualAccountNumber((String) meta.get("virtual_account_number"))
                    .virtualAccountName((String) meta.get("virtual_account_name"))
                    .virtualAccountBankName((String) meta.get("virtual_account_bank_name"))
                    .virtualAccountBankCode((String) meta.get("virtual_account_bank_code"))
                    .virtualAccountExpiryDate((String) meta.get("virtual_account_expiry_date"))

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
    public String createMerchant(CreateBranchRequest requestDetails, String transactionRef) {
        String url = baseUrl + "/transact";
        String requestRef = UUID.randomUUID().toString();

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
                                .webhookUrl("https://your-app-url.com/api/webhooks/onepipe") // TODO Update it
                                .whatsappContactName(requestDetails.getContactFirstName())
                                .whatsappContactNo(requestDetails.getContactPhoneNumber())
                                .businessShortName(requestDetails.getBusinessShortName())
                                .build())
                        .details(OnePipeMerchantRequest.Details.builder()
                                .businessName(requestDetails.getBusinessName())
                                .rcNumber(requestDetails.getRcNumber())

                                .settlementAccountNo(requestDetails.getSettlementAccountNumber())
                                .settlementBankCode(requestDetails.getSettlementBankCode())
                                .taxId(requestDetails.getTin())
                                .address(requestDetails.getAddress())
                                .notificationPhone(requestDetails.getContactPhoneNumber())
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

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("API Call Failed: " + e.getMessage());
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
}
