package com.onepipe.data.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Table(name = "branches")
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Branch extends BaseEntity {
    @OneToOne
    @JoinColumn(name = "admin_user_id")
    private User adminUser;

    private String businessName;
    private String businessShortName;
    private String businessAddress;

    @Column(unique = true)
    private String rcNumber;
    private String tin;

    private String contactFirstName;
    private String contactSurname;
    private String contactEmail;
    private String contactPhoneNumber;

    private String settlementAccountNumber;
    private String settlementBankCode;

    @Column(unique = true)
    private String requestRef;
    @Column(unique = true)
    private String transactionRef;

    private String merchantId;


}
