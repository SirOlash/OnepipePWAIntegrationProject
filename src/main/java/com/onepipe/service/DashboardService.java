package com.onepipe.service;

import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.dtos.response.SuperAdminStatsResponse;
import lombok.RequiredArgsConstructor;
import com.onepipe.security.ResourceAuthorization;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final BranchRepository branchRepository;
    private final StudentRepository studentRepository;
    private final PaymentRepository paymentRepository;
    private final ResourceAuthorization authorization;

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SuperAdminStatsResponse getSuperAdminStats() {
        authorization.requireSuperAdmin();
        // 1. Get Counts
        long totalBranches = branchRepository.count();
        long totalStudents = studentRepository.count();

        // 2. Get Payment Stats
        long activePayments = paymentRepository.countActivePayments();
        BigDecimal totalRevenue = paymentRepository.calculateTotalRevenue();

        // 3. Build Response
        return SuperAdminStatsResponse.builder()
                .totalBranches(totalBranches)
                .totalStudents(totalStudents)
                .totalRevenue(totalRevenue)
                .activePayments(activePayments)
                .build();
    }
}

