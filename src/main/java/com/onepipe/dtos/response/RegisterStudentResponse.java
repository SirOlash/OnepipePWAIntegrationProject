package com.onepipe.dtos.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterStudentResponse {
    private String studentRegId;
    private String studentName;
    private String parentName;
    private String message;
}
