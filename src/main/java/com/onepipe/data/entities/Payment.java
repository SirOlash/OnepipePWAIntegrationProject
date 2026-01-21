package com.onepipe.data.entities;

import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Parent parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Enumerated(EnumType.STRING)
    private PaymentCategory category;

    @Enumerated(EnumType.STRING)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private BigDecimal totalAmount;

    private BigDecimal downPaymentAmount;
    private BigDecimal amountPerCycle;

    @Enumerated(EnumType.STRING)
    private InstallmentFrequency installmentFrequency;

    private Integer numberOfPayments;

    private Integer completedPayments;

    private String description;

    @Column(unique = true)
    private String requestRef;

    @Column(unique = true)
    private String transactionRef;

    private String onePipePaymentId;

    private String subscriptionId;

    private String virtualAccountNumber;
}
