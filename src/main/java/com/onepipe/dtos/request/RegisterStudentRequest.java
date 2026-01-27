package com.onepipe.dtos.request;

import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.Title;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterStudentRequest {
    private Long branchId;
    private String firstName;
    private String surname;
    private LocalDate dateOfBirth;
    private ClassGrade classGrade;

    private Title parentTitle;
    private String parentFirstName;
    private String parentSurname;
    private String parentEmail;
    private String parentPassword;
    private String parentPhoneNumber;

    private PaymentType paymentType;
    private InstallmentFrequency frequency;
    private Integer numberOfInstallments;

    private String bankAccountNumber;
    private String bankCode;
}
