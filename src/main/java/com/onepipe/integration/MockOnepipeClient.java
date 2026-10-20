package com.onepipe.integration;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local demo state only: no provider calls, transfers, or automatic settlement.
 * References are idempotent for this application instance; restarting clears the state.
 */
@Service
@ConditionalOnProperty(prefix = "onepipe", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockOnepipeClient implements OnepipeClient {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PENDING_MESSAGE = "DEMO ONLY: mock invoice is pending; no payment was processed.";
    private static final String CANCELLED_MESSAGE = "DEMO ONLY: mock subscription is cancelled; no payment was processed.";
    private static final String NOT_FOUND_MESSAGE =
            "Mock transaction not found in this application instance; no payment was processed.";

    private final ConcurrentMap<String, String> merchants = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MockInvoice> invoices = new ConcurrentHashMap<>();

    @Override
    public String createMerchant(CreateBranchRequest requestDetails, String transactionRef, String requestRef) {
        if (requestDetails == null) {
            throw new IllegalArgumentException("Mock merchant request is required");
        }
        requireReference(transactionRef, "transactionRef");
        requireReference(requestRef, "requestRef");
        return merchants.computeIfAbsent(requestRef, ignored -> "MOCK-MERCHANT-" + UUID.randomUUID());
    }

    @Override
    public OnePipeResponse sendInvoice(OnePipeInvoiceRequest request) {
        if (request == null || request.getTransaction() == null) {
            throw new IllegalArgumentException("Mock invoice request and transaction are required");
        }
        requireReference(request.getRequestRef(), "requestRef");
        String transactionRef = requireReference(request.getTransaction().getTransactionRef(), "transactionRef");
        return invoices.computeIfAbsent(transactionRef, ignored -> newInvoice(request, transactionRef)).toResponse();
    }

    @Override
    public OnePipeResponse queryTransaction(String transactionRef) {
        requireReference(transactionRef, "transactionRef");
        MockInvoice invoice = invoices.get(transactionRef);
        if (invoice == null) {
            return OnePipeResponse.builder()
                    .transactionRef(transactionRef)
                    .status("NotFound")
                    .message(NOT_FOUND_MESSAGE)
                    .build();
        }
        return invoice.toResponse();
    }

    @Override
    public boolean cancelMandate(Payment payment) {
        if (payment == null || payment.getPaymentType() != PaymentType.SUBSCRIPTION) {
            return false;
        }
        String transactionRef = payment.getTransactionRef();
        String paymentId = payment.getOnePipePaymentId();
        if (transactionRef == null || transactionRef.isBlank() || paymentId == null || paymentId.isBlank()) {
            return false;
        }

        MockInvoice result = invoices.computeIfPresent(transactionRef, (ignored, invoice) ->
                invoice.subscription() && invoice.paymentId().equals(paymentId) ? invoice.cancel() : invoice);
        return result != null && result.subscription() && result.paymentId().equals(paymentId) && result.cancelled();
    }

    private MockInvoice newInvoice(OnePipeInvoiceRequest request, String transactionRef) {
        OnePipeInvoiceRequest.Transaction transaction = request.getTransaction();
        String customerEmail = transaction.getCustomer() == null ? null : transaction.getCustomer().getEmail();
        if (customerEmail == null || customerEmail.isBlank()) {
            customerEmail = "demo-customer@example.invalid";
        }
        boolean subscription = transaction.getMeta() != null
                && "subscription".equalsIgnoreCase(transaction.getMeta().getType());
        LocalDateTime createdOn = LocalDateTime.now();
        // A demo lifetime, independent of any undocumented provider expiry semantics.
        return new MockInvoice("MOCK-PAY-" + UUID.randomUUID(), transactionRef, customerEmail,
                createdOn.format(DATE_FORMAT), createdOn.plusHours(1).format(DATE_FORMAT), subscription, false);
    }

    private static String requireReference(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for mock processing");
        }
        return value;
    }

    private record MockInvoice(String paymentId, String transactionRef, String customerEmail, String createdOn,
                               String expiresOn, boolean subscription, boolean cancelled) {

        private MockInvoice cancel() {
            return cancelled ? this : new MockInvoice(paymentId, transactionRef, customerEmail, createdOn,
                    expiresOn, subscription, true);
        }

        private OnePipeResponse toResponse() {
            return OnePipeResponse.builder()
                    .paymentId(paymentId)
                    .transactionRef(transactionRef)
                    .virtualAccountNumber("DEMO-ACCOUNT-" + paymentId)
                    .virtualAccountBankName("DEMO ONLY - no transfers")
                    .virtualAccountName("DEMO ONLY - no transfers")
                    .virtualAccountBankCode("DEMO-NOT-A-BANK")
                    .virtualAccountExpiryDate(expiresOn)
                    .customerAccountNumber("DEMO-CUSTOMER-" + paymentId)
                    .customerEmail(customerEmail)
                    .createdOn(createdOn)
                    .status(cancelled ? "Cancelled" : "Pending")
                    .message(cancelled ? CANCELLED_MESSAGE : PENDING_MESSAGE)
                    .build();
        }
    }
}
