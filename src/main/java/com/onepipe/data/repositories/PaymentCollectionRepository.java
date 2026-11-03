package com.onepipe.data.repositories;

import com.onepipe.data.entities.PaymentCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PaymentCollectionRepository extends JpaRepository<PaymentCollection, Long> {
    Optional<PaymentCollection> findByEventId(String eventId);
    Optional<PaymentCollection> findByPaymentIdAndCollectionSlot(Long paymentId, String collectionSlot);
    List<PaymentCollection> findByPaymentId(Long paymentId);
}
