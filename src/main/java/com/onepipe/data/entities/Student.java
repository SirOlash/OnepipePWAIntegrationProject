package com.onepipe.data.entities;


import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "students")
public class Student extends BaseEntity{
    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String surname;

    private LocalDate dateOfBirth;

    @Column(nullable = false, unique = true)
    private String studentRegId;

    @Enumerated(EnumType.STRING)
    private ClassGrade classGrade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Parent parent;

    private Integer numberOfPayments;
    private Integer completedPayments;

    @Enumerated(EnumType.STRING)
    private PaymentType preferredPaymentType;

    @Enumerated(EnumType.STRING)
    private InstallmentFrequency installmentFrequency;

    private Integer numberOfInstallments;

}
