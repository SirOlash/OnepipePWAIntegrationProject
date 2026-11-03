package com.onepipe.data.entities;

import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends BaseEntity {
    /** Null on historical rows: no claim that legacy counters are collection evidence. */
    private Integer accountingVersion;

    /** Africa/Lagos billing snapshot for newly created arrangements; null on historical rows. */
    private LocalDateTime billingAnchor;

    /** Account/instruction expiry boundary recorded on observation; independent of plan state. */
    private Instant virtualAccountExpiredAt;

    @Version
    @Column(nullable = false)
    private Long rowVersion;

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

    private String virtualAccountNumber;
    private String virtualAccountName;
    private String virtualAccountBankName;
    private String virtualAccountBankCode;
    private String onePipeReference;
    private String customerAccountNumber;
    private String virtualAccountExpiryDate;
    @Column(columnDefinition = "TEXT")
    private String virtualAccountQrCodeUrl;
}
