package com.onepipe.dtos.response;

import com.onepipe.data.enums.ClassGrade;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ParentStudentDto {
    private Long id;
    private String firstName;
    private String surname;
    private ClassGrade classGrade;
    private String status;
    private BigDecimal pendingAmount;
}
