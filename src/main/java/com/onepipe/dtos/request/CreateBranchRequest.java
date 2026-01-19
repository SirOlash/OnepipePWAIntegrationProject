package com.onepipe.dtos.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateBranchRequest {
    private String adminEmail;
    private String adminPassword;

    private String businessName;
    private String businessShortName;
    private String address;
    private String rcNumber;
    private String tin;

    private String contactFirstName;
    private String contactSurname;
    private String contactPhoneNumber;

    private String settlementAccountNumber;
    private String settlementBankCode;
}
