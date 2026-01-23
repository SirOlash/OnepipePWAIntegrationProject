package com.onepipe.service;

import com.onepipe.data.entities.*;
import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.dtos.request.RegisterStudentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private ParentRepository parentRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PaymentService paymentService; // <--- We mock this to check if it's called

    @InjectMocks
    private StudentService studentService;

    @Test
    void testRegisterStudent_TriggersPayment() {
        RegisterStudentRequest request = new RegisterStudentRequest();
        request.setBranchId(1L);
        request.setParentEmail("mom@test.com");
        request.setParentPhoneNumber("080123");
        request.setFirstName("Junior");
        request.setClassGrade(ClassGrade.JSS1);
        request.setPaymentType(PaymentType.SINGLE_PAYMENT);

        Branch mockBranch = new Branch();
        mockBranch.setBusinessShortName("GRN");

        when(branchRepository.findById(1L)).thenReturn(Optional.of(mockBranch));
        // Simulate Parent not found (create new)
        when(parentRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(new User());
        when(parentRepository.save(any(Parent.class))).thenReturn(new Parent());

        // Mock Student Save
        when(studentRepository.save(any(Student.class))).thenAnswer(i -> i.getArguments()[0]);

        // 2. EXECUTE
        studentService.registerStudent(request);

        // 3. VERIFY
        // Check that PaymentService was called exactly once
        verify(paymentService, times(1)).createInitialSchoolFeesPayment(any(Student.class));

        System.out.println("✅ Student Registration correctly triggers Payment Service");
    }
}