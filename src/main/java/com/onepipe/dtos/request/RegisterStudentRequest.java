package com.onepipe.dtos.request;

import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.Title;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.onepipe.validation.FinancialInput;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterStudentRequest {
    @NotNull(groups = FinancialInput.class) @Positive(groups = FinancialInput.class)
    private Long branchId;
    @NotBlank(groups = FinancialInput.class) @Size(max = 255, groups = FinancialInput.class)
    private String firstName;
    @NotBlank(groups = FinancialInput.class) @Size(max = 255, groups = FinancialInput.class)
    private String surname;
    @NotNull(groups = FinancialInput.class) @Past(groups = FinancialInput.class)
    private LocalDate dateOfBirth;
    @NotNull(groups = FinancialInput.class)
    private ClassGrade classGrade;

    private Title parentTitle;
    @NotBlank(groups = FinancialInput.class) @Size(max = 255, groups = FinancialInput.class)
    private String parentFirstName;
    @NotBlank(groups = FinancialInput.class) @Size(max = 255, groups = FinancialInput.class)
    private String parentSurname;
    @NotBlank(groups = FinancialInput.class) @Email(groups = FinancialInput.class)
    @Size(max = 255, groups = FinancialInput.class)
    private String parentEmail;
    private String parentPassword;
    @NotBlank(groups = FinancialInput.class) @Size(max = 255, groups = FinancialInput.class)
    private String parentPhoneNumber;

    @NotNull(groups = FinancialInput.class)
    private PaymentType paymentType;
    private InstallmentFrequency frequency;
    @JsonAlias("numberOfPayments")
    @Positive(groups = FinancialInput.class)
    @Max(value = com.onepipe.service.PaymentCalculator.MAX_INSTALLMENTS, groups = FinancialInput.class)
    private Integer numberOfInstallments;
    @Positive(groups = FinancialInput.class)
    @Digits(integer = 36, fraction = 2, groups = FinancialInput.class)
    private BigDecimal amount;

    @Size(max = 255, groups = FinancialInput.class)
    private String bankAccountNumber;
    @Size(max = 255, groups = FinancialInput.class)
    private String bankCode;
}
