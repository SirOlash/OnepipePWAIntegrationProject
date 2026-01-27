package com.onepipe.dtos.response;

import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.PaymentType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BranchStudentDto {
    private Long id;
    private String firstName;
    private String surname;
    private ClassGrade classGrade;
    private String parentEmail;
    private String parentFullName;
    private PaymentType paymentType;
    private String status;
}
