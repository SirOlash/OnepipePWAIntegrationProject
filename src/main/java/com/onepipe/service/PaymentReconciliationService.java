package com.onepipe.service;

import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeQueryResponse;
import com.onepipe.integration.dto.OnePipeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.Objects;

/** Read-only diagnostics: adapter metadata is never evidence of a collection. */
@Service
public class PaymentReconciliationService {
    private final PaymentReconciliationSnapshot snapshots;
    private final OnepipeClient provider;
    private final String mode;

    public PaymentReconciliationService(PaymentReconciliationSnapshot snapshots, OnepipeClient provider,
            @Value("${onepipe.mode:mock}") String mode) {
        this.snapshots = snapshots;
        this.provider = provider;
        this.mode = mode;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OnePipeQueryResponse query(String onePipeId) {
        var snapshot = snapshots.readAuthorized(onePipeId);
        var response = OnePipeQueryResponse.builder().status(snapshot.status()).requestRef(snapshot.requestRef())
                .transactionRef(snapshot.transactionRef()).onePipePaymentId(snapshot.onePipePaymentId())
                .confirmedAmount(snapshot.confirmedAmount()).remainingAmount(snapshot.remainingAmount())
                .balanceBasis(snapshot.balanceBasis()).invoiceWindowStatus(snapshot.invoiceWindowStatus())
                .localStateConsistent(snapshot.localStateConsistent())
                .providerAvailability("UNAVAILABLE")
                .message(Boolean.FALSE.equals(snapshot.localStateConsistent())
                        ? "Read-only snapshot; local lifecycle is inconsistent with ledger accounting. Adapter metadata is unavailable."
                        : "Read-only local accounting snapshot; adapter metadata is unavailable. No collection was verified.")
                .build();
        // Real-provider diagnostics are not verified/supported by this offline demonstration.
        if (!"mock".equalsIgnoreCase(mode)) return response;
        OnePipeResponse metadata;
        try {
            metadata = provider.queryTransaction(snapshot.transactionRef());
        } catch (RuntimeException unavailable) {
            // Do not propagate/log adapter messages, identifiers, response bodies or credentials.
            return response;
        }
        if (metadata == null || metadata.getStatus() == null || "NotFound".equalsIgnoreCase(metadata.getStatus())) {
            return response;
        }
        String status = metadata.getStatus().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "ACTIVE", "SUCCESSFUL", "CANCELLED", "FAILED").contains(status)) status = "UNKNOWN";
        boolean consistent = status.equals(snapshot.status())
                && Objects.equals(metadata.getTransactionRef(), snapshot.transactionRef())
                && Objects.equals(metadata.getPaymentId(), snapshot.onePipePaymentId());
        response.setProviderAvailability("AVAILABLE");
        response.setProviderStatus(status);
        response.setProviderStateConsistent(consistent);
        response.setMessage(Boolean.FALSE.equals(snapshot.localStateConsistent())
                ? "Read-only diagnostics; local lifecycle is inconsistent with ledger accounting. No accounting was changed."
                : consistent
                ? "Read-only local accounting snapshot; adapter metadata agrees. No bank collection was verified."
                : "Read-only local accounting snapshot; adapter metadata is inconsistent. Local accounting was not changed.");
        return response;
    }
}
