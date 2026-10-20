package com.onepipe.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MockOnepipeClientTest {

    private final MockOnepipeClient client = new MockOnepipeClient();

    @Test
    void merchantCreationIsStableForTheSameRequestReferenceAndSeparateForOtherRequests() {
        CreateBranchRequest request = CreateBranchRequest.builder().businessName("Demo Campus").build();
        String merchant = client.createMerchant(request, "merchant-tx-1", "merchant-request-1");

        request.setBusinessName("Changed display name");
        assertThat(client.createMerchant(request, "merchant-tx-2", "merchant-request-1")).isEqualTo(merchant);
        assertThat(merchant).startsWith("MOCK-MERCHANT-");
        assertThat(client.createMerchant(request, "merchant-tx-3", "merchant-request-2"))
                .startsWith("MOCK-MERCHANT-").isNotEqualTo(merchant);
    }

    @Test
    void invoicesReturnCompletePendingDemoDetailsAndConsistentQueryResults() {
        OnePipeInvoiceRequest request = invoice("invoice-1", "single_payment");
        OnePipeResponse response = client.sendInvoice(request);

        assertThat(response.getPaymentId()).startsWith("MOCK-PAY-");
        assertThat(response.getTransactionRef()).isEqualTo("invoice-1");
        assertThat(response.getStatus()).isEqualTo("Pending");
        assertThat(response.getMessage()).contains("DEMO ONLY", "no payment was processed");
        assertThat(response.getVirtualAccountNumber()).startsWith("DEMO-ACCOUNT-").doesNotMatch("\\d+");
        assertThat(response.getCustomerAccountNumber()).startsWith("DEMO-CUSTOMER-").doesNotMatch("\\d+");
        assertThat(response.getVirtualAccountBankName()).contains("DEMO ONLY", "no transfers");
        assertThat(response.getVirtualAccountName()).contains("DEMO ONLY", "no transfers");
        assertThat(response.getVirtualAccountBankCode()).isEqualTo("DEMO-NOT-A-BANK");
        assertThat(response.getCustomerEmail()).isEqualTo("demo-parent@example.invalid");
        assertThat(response.getVirtualAccountQrCodeUrl()).isNull();

        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime createdOn = LocalDateTime.parse(response.getCreatedOn(), format);
        LocalDateTime expiresOn = LocalDateTime.parse(response.getVirtualAccountExpiryDate(), format);
        assertThat(Duration.between(createdOn, expiresOn)).isEqualTo(Duration.ofHours(1));
        assertThat(client.queryTransaction("invoice-1")).isEqualTo(response).isNotSameAs(response);
        assertThat(client.sendInvoice(request)).isEqualTo(response).isNotSameAs(response);
    }

    @Test
    void responseSerializationPreservesTheExistingFieldContract() {
        OnePipeResponse response = client.sendInvoice(invoice("serialized-invoice", "instalment"));
        JsonNode json = new ObjectMapper().valueToTree(response);
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "virtualAccountNumber", "virtualAccountBankName", "virtualAccountName", "virtualAccountBankCode",
                "virtualAccountExpiryDate", "customerAccountNumber", "customerEmail", "transactionRef",
                "paymentId", "status", "message", "createdOn", "virtualAccountQrCodeUrl"
        );
        assertThat(json.get("transactionRef").asText()).isEqualTo("serialized-invoice");
        assertThat(json.get("paymentId").asText()).isEqualTo(response.getPaymentId());
        assertThat(json.get("status").asText()).isEqualTo("Pending");
        assertThat(json.get("virtualAccountNumber").asText()).startsWith("DEMO-ACCOUNT-");
        assertThat(json.get("virtualAccountQrCodeUrl").isNull()).isTrue();
    }

    @Test
    void separateInvoicesHaveSeparateIdentifiersAndAccounts() {
        OnePipeResponse first = client.sendInvoice(invoice("invoice-first", "single_payment"));
        OnePipeResponse second = client.sendInvoice(invoice("invoice-second", "subscription"));

        assertThat(second.getPaymentId()).isNotEqualTo(first.getPaymentId());
        assertThat(second.getVirtualAccountNumber()).isNotEqualTo(first.getVirtualAccountNumber());
        assertThat(second.getCustomerAccountNumber()).isNotEqualTo(first.getCustomerAccountNumber());
        assertThat(client.queryTransaction("invoice-first")).isEqualTo(first);
        assertThat(client.queryTransaction("invoice-second")).isEqualTo(second);
    }

    @Test
    void requestAndResponseMutationCannotChangeStoredInvoiceState() {
        OnePipeInvoiceRequest request = invoice("immutable-invoice", "subscription");
        OnePipeResponse original = client.sendInvoice(request);
        OnePipeResponse expected = client.queryTransaction("immutable-invoice");

        original.setStatus("Successful");
        original.setPaymentId("changed-payment");
        original.setVirtualAccountNumber("changed-account");
        request.setRequestRef("new-request-reference");
        request.getTransaction().getCustomer().setEmail("changed@example.invalid");
        request.getTransaction().getMeta().setType("single_payment");
        assertThat(client.sendInvoice(request)).isEqualTo(expected);

        OnePipeResponse queried = client.queryTransaction("immutable-invoice");
        queried.setStatus("Successful");
        queried.setCustomerEmail("changed@example.invalid");
        queried.setVirtualAccountExpiryDate("changed-expiry");
        assertThat(client.queryTransaction("immutable-invoice")).isEqualTo(expected);
        assertThat(client.cancelMandate(subscription(expected))).isTrue();
    }

    @Test
    void unknownQueriesNeverReportSuccessAndDoNotCreateInvoiceState() {
        OnePipeResponse unknown = client.queryTransaction("unknown-invoice");

        assertThat(unknown.getTransactionRef()).isEqualTo("unknown-invoice");
        assertThat(unknown.getStatus()).isEqualTo("NotFound");
        assertThat(unknown.getMessage()).contains("not found", "this application instance", "no payment was processed");
        assertThat(unknown.getPaymentId()).isNull();
        assertThat(unknown.getVirtualAccountNumber()).isNull();
        assertThat(client.queryTransaction("unknown-invoice")).isEqualTo(unknown).isNotSameAs(unknown);
        assertThat(client.sendInvoice(invoice("unknown-invoice", "single_payment")).getStatus()).isEqualTo("Pending");
    }

    @Test
    void restartingTheMockDoesNotPretendPersistedInvoicesAreKnownOrPaid() {
        OnePipeResponse response = client.sendInvoice(invoice("before-restart", "subscription"));
        MockOnepipeClient restarted = new MockOnepipeClient();

        assertThat(restarted.queryTransaction(response.getTransactionRef()).getStatus()).isEqualTo("NotFound");
        assertThat(restarted.cancelMandate(subscription(response))).isFalse();
    }

    @Test
    void cancellationIsIdempotentAndVisibleToQueriesAndRepeatedInvoiceRequests() {
        OnePipeInvoiceRequest request = invoice("cancellable-invoice", "subscription");
        OnePipeResponse pending = client.sendInvoice(request);
        Payment payment = subscription(pending);

        assertThat(client.cancelMandate(payment)).isTrue();
        OnePipeResponse cancelled = client.queryTransaction("cancellable-invoice");
        assertThat(cancelled.getStatus()).isEqualTo("Cancelled");
        assertThat(cancelled.getMessage()).contains("cancelled", "no payment was processed");
        assertThat(cancelled).usingRecursiveComparison().ignoringFields("status", "message").isEqualTo(pending);
        assertThat(pending.getStatus()).isEqualTo("Pending");
        assertThat(client.cancelMandate(payment)).isTrue();
        assertThat(client.queryTransaction("cancellable-invoice")).isEqualTo(cancelled);
        assertThat(client.sendInvoice(request)).isEqualTo(cancelled);
    }

    @Test
    void cancellationRejectsUnknownMismatchedAndNonSubscriptionPayments() {
        OnePipeResponse subscription = client.sendInvoice(invoice("subscription-invoice", "subscription"));
        OnePipeResponse single = client.sendInvoice(invoice("single-invoice", "single_payment"));

        assertThat(client.cancelMandate(null)).isFalse();
        assertThat(client.cancelMandate(Payment.builder().paymentType(PaymentType.SUBSCRIPTION).build())).isFalse();
        assertThat(client.cancelMandate(Payment.builder().paymentType(PaymentType.SUBSCRIPTION)
                .transactionRef("unknown-invoice").onePipePaymentId(subscription.getPaymentId()).build())).isFalse();
        assertThat(client.cancelMandate(Payment.builder().paymentType(PaymentType.SUBSCRIPTION)
                .transactionRef(subscription.getTransactionRef()).onePipePaymentId(single.getPaymentId()).build())).isFalse();
        assertThat(client.cancelMandate(Payment.builder().paymentType(PaymentType.SINGLE_PAYMENT)
                .transactionRef(subscription.getTransactionRef()).onePipePaymentId(subscription.getPaymentId()).build()))
                .isFalse();
        assertThat(client.cancelMandate(subscription(single))).isFalse();
        assertThat(client.queryTransaction(subscription.getTransactionRef())).isEqualTo(subscription);
        assertThat(client.queryTransaction(single.getTransactionRef())).isEqualTo(single);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankReferencesAreRejected(String reference) {
        CreateBranchRequest merchant = new CreateBranchRequest();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.createMerchant(merchant, reference, "valid-request"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.createMerchant(merchant, "valid-transaction", reference));
        assertThatIllegalArgumentException().isThrownBy(() -> client.queryTransaction(reference));

        OnePipeInvoiceRequest missingRequestRef = invoice("valid-transaction", "single_payment");
        missingRequestRef.setRequestRef(reference);
        assertThatIllegalArgumentException().isThrownBy(() -> client.sendInvoice(missingRequestRef));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.sendInvoice(invoice(reference, "single_payment")));
    }

    @Test
    void missingRequestStructuresAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.createMerchant(null, "merchant-transaction", "merchant-request"));
        assertThatIllegalArgumentException().isThrownBy(() -> client.sendInvoice(null));
        assertThatIllegalArgumentException().isThrownBy(() -> client.sendInvoice(new OnePipeInvoiceRequest()));
    }

    @Test
    void concurrentRepeatedInvoiceRequestsShareOneStableSnapshot() throws Exception {
        OnePipeInvoiceRequest request = invoice("concurrent-invoice", "single_payment");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<OnePipeResponse>> calls = IntStream.range(0, 32)
                    .mapToObj(index -> (Callable<OnePipeResponse>) () -> client.sendInvoice(request)).toList();
            List<Future<OnePipeResponse>> futures = executor.invokeAll(calls, 10, TimeUnit.SECONDS);
            List<OnePipeResponse> responses = new ArrayList<>();
            for (Future<OnePipeResponse> future : futures) {
                responses.add(future.get(1, TimeUnit.SECONDS));
            }

            OnePipeResponse expected = client.queryTransaction("concurrent-invoice");
            assertThat(responses).hasSize(32).allSatisfy(response ->
                    assertThat(response).isEqualTo(expected).isNotSameAs(expected));
            assertThat(responses.get(0)).isNotSameAs(responses.get(1));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static OnePipeInvoiceRequest invoice(String transactionRef, String type) {
        OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();
        request.setRequestRef("request-" + transactionRef);
        OnePipeInvoiceRequest.Transaction transaction = new OnePipeInvoiceRequest.Transaction();
        transaction.setTransactionRef(transactionRef);
        transaction.setAmount(new BigDecimal("100000.00"));
        OnePipeInvoiceRequest.Customer customer = new OnePipeInvoiceRequest.Customer();
        customer.setEmail("demo-parent@example.invalid");
        transaction.setCustomer(customer);
        OnePipeInvoiceRequest.Meta meta = new OnePipeInvoiceRequest.Meta();
        meta.setType(type);
        transaction.setMeta(meta);
        request.setTransaction(transaction);
        return request;
    }

    private static Payment subscription(OnePipeResponse response) {
        return Payment.builder().paymentType(PaymentType.SUBSCRIPTION)
                .transactionRef(response.getTransactionRef()).onePipePaymentId(response.getPaymentId()).build();
    }
}
