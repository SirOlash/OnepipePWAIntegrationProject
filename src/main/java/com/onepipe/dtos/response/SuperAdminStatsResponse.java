package com.onepipe.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SuperAdminStatsResponse {
    private Long totalBranches;
    private Long totalStudents;
    private BigDecimal totalRevenue;
    private Long activePayments;
}
