package com.onepipe.data.entities;

import com.onepipe.data.enums.CollectionSource;
import com.onepipe.data.enums.CollectionType;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Append-only confirmed simulated collections; not failed attempts or planned obligations. */
@Entity
@org.hibernate.annotations.Check(constraints = "confirmed_amount > 0 and currency = 'NGN'")
@Table(name = "payment_collections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collection_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_collection_slot", columnNames = {"payment_id", "collection_slot"})})
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentCollection extends BaseEntity {
    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;
    @Column(nullable = false, updatable = false, length = 100)
    private String transactionRef;
    @Column(name = "collection_slot", nullable = false, updatable = false, length = 50)
    private String collectionSlot;
    @Column(nullable = false, updatable = false, precision = 38, scale = 2)
    private BigDecimal confirmedAmount;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 24)
    private CollectionSource source;
    @Column(nullable = false, updatable = false)
    private Instant confirmedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 24)
    private CollectionType collectionType;
}
