package com.onepipe.dtos.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateBranchResponse {
    private Long id;
    private String businessName;
    private String branchCode;
    private String merchantId;
    private String adminEmail;
    private String contactPersonName;
}
