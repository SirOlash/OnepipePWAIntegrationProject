package com.onepipe.controllers;

import com.onepipe.dtos.request.RegisterStudentRequest;
import com.onepipe.dtos.response.BranchStudentDto;
import com.onepipe.dtos.response.ParentStudentDto;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    @PostMapping("/register")
    public ResponseEntity<RegisterStudentResponse> registerStudent(@RequestBody RegisterStudentRequest request) {
        return ResponseEntity.ok(studentService.registerStudent(request));
    }

    @GetMapping
    // Allow Super Admin OR Branch Admin (Frontend handles showing the right ID)
    public ResponseEntity<List<BranchStudentDto>> getStudentsByBranch(
            @RequestParam Long branchId
    ) {
        return ResponseEntity.ok(studentService.getStudentsByBranch(branchId));
    }

    @GetMapping(params = "parentEmail")
    public ResponseEntity<List<ParentStudentDto>> getStudentsByParent(
            @RequestParam String parentEmail
    ) {
        return ResponseEntity.ok(studentService.getStudentsByParentEmail(parentEmail));
    }
}
