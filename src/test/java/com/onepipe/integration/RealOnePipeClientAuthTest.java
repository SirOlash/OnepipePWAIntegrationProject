package com.onepipe.integration;

import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.utils.EncryptionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RealOnePipeClientAuthTest {

    @ParameterizedTest
    @EnumSource(PaymentType.class)
    void alreadyConvertedKoboPassesThroughRealAdapterWithoutAnotherConversion(PaymentType type) {
        var client = new CapturingRealClient();
        ReflectionTestUtils.setField(client, "clientSecret", UUID.randomUUID().toString());
        var request = new OnePipeInvoiceRequest(); var tx = new OnePipeInvoiceRequest.Transaction();
        tx.setAmount(new BigDecimal("10000000"));
        var meta = new OnePipeInvoiceRequest.Meta(); meta.setDownPayment(new BigDecimal("2000000"));
        tx.setMeta(meta); request.setTransaction(tx);
        var payment = Payment.builder().paymentType(type).parent(Parent.builder()
                .bankAccountNumber("SYNTHETIC-ACCOUNT").bankCode("SYNTHETIC-BANK").build()).build();
        client.sendInvoice(request, payment);
        assertThat(client.sentRequest.getTransaction().getAmount()).isEqualTo(new BigDecimal("10000000"));
        assertThat(client.sentRequest.getTransaction().getMeta().getDownPayment()).isEqualTo(new BigDecimal("2000000"));
    }

    @Test
    void singlePaymentKeepsProviderAuthorizationWithoutBankDetails() {
        CapturingRealClient client = new CapturingRealClient();
        OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();
        Payment payment = Payment.builder().paymentType(PaymentType.SINGLE_PAYMENT).build();

        OnePipeResponse response = client.sendInvoice(request, payment);

        assertThat(response).isSameAs(client.transportResponse);
        assertThat(client.sentRequest).isSameAs(request);
        assertThat(request.getAuth()).isNotNull();
        assertThat(request.getAuth().getAuthProvider()).isEqualTo("PaywithAccount");
        assertThat(request.getAuth().getType()).isNull();
        assertThat(request.getAuth().getSecure()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentType.class, names = {"INSTALLMENT", "SUBSCRIPTION"})
    void recurringPaymentsKeepExistingBankAuthorizationAtTheRealAdapter(PaymentType paymentType) {
        String testOnlySecret = UUID.randomUUID().toString();
        CapturingRealClient client = new CapturingRealClient();
        ReflectionTestUtils.setField(client, "clientSecret", testOnlySecret);
        Parent parent = Parent.builder()
                .bankAccountNumber("SYNTHETIC-ACCOUNT")
                .bankCode("SYNTHETIC-BANK")
                .build();
        Payment payment = Payment.builder().paymentType(paymentType).parent(parent).build();
        OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();

        OnePipeResponse response = client.sendInvoice(request, payment);

        assertThat(response).isSameAs(client.transportResponse);
        assertThat(client.sentRequest).isSameAs(request);
        assertThat(request.getAuth().getAuthProvider()).isEqualTo("PaywithAccount");
        assertThat(request.getAuth().getType()).isEqualTo("bank.account");
        assertThat(request.getAuth().getSecure()).isEqualTo(EncryptionUtil.encryptTripleDES(
                "SYNTHETIC-ACCOUNT;SYNTHETIC-BANK", testOnlySecret));
    }

    /** Captures the transport boundary so these tests cannot call the provider. */
    private static class CapturingRealClient extends RealOnePipeClient {

        private final OnePipeResponse transportResponse = OnePipeResponse.builder().status("Pending").build();
        private OnePipeInvoiceRequest sentRequest;

        @Override
        public OnePipeResponse sendInvoice(OnePipeInvoiceRequest request) {
            sentRequest = request;
            return transportResponse;
        }
    }
}
