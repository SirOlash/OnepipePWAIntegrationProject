package com.onepipe.controllers;

import com.onepipe.dtos.request.RegisterStudentRequest;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    @PostMapping("/register")
    public ResponseEntity<RegisterStudentResponse> registerStudent(@RequestBody RegisterStudentRequest request) {
        return ResponseEntity.ok(studentService.registerStudent(request));
    }
}
