package com.onepipe.controllers;

import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.dtos.response.CreateBranchResponse;
import com.onepipe.service.BranchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CreateBranchResponse> createBranch(@RequestBody CreateBranchRequest request) {
        return ResponseEntity.ok(branchService.createBranch(request));
    }

    @GetMapping
//    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<CreateBranchResponse>> getAllBranches() {
        return ResponseEntity.ok(branchService.getAllBranches());
    }
}
