package com.onepipe.service;


import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Student;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.dtos.request.RegisterStudentRequest;
import com.onepipe.dtos.response.RegisterStudentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final PaymentService paymentService;

    @Transactional
    public RegisterStudentResponse registerStudent(RegisterStudentRequest request) {

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        if (request.getPaymentType() != PaymentType.SINGLE_PAYMENT) {
            if (request.getBankAccountNumber() == null || request.getBankCode() == null) {
                throw new RuntimeException("Bank details are required for Installment/Subscription");
            }
        }

        Parent parent = parentRepository.findByPhoneNumber(request.getParentPhoneNumber())
                .or(() -> parentRepository.findByUser_Email(request.getParentEmail()))
                .orElse(null);

        if (parent == null) {
            parent = createSafeParent(request);
        }

        String regId = generateStudentId(branch.getBusinessShortName());

        Student student = Student.builder()
                .firstName(request.getFirstName())
                .surname(request.getSurname())
                .dateOfBirth(request.getDateOfBirth())
                .studentRegId(regId)
                .classGrade(request.getClassGrade())
                .branch(branch)
                .parent(parent)
                .preferredPaymentType(request.getPaymentType())
                .installmentFrequency(request.getFrequency())
                .numberOfInstallments(request.getNumberOfInstallments())
                .build();

        studentRepository.save(student);

        paymentService.createInitialSchoolFeesPayment(student);

        return RegisterStudentResponse.builder()
                .studentRegId(regId)
                .studentName(student.getFirstName() + " " + student.getSurname())
                .parentName(parent.getFirstName() + " " + parent.getSurname())
                .message("Registration Successful! Check email for details.")
                .build();
    }

    private Parent createSafeParent(RegisterStudentRequest request) {
        User user = userRepository.findByEmail(request.getParentEmail()).orElse(null);
        if (user == null) {
            user = User.builder()
                    .email(request.getParentEmail())
                    .password(passwordEncoder.encode(request.getParentPassword()))
                    .role(Role.PARENT)
                    .build();
            user = userRepository.save(user);
        }
            Parent parent = Parent.builder()
                    .user(user)
                    .title(request.getParentTitle())
                    .firstName(request.getParentFirstName())
                    .surname(request.getParentSurname())
                    .phoneNumber(request.getParentPhoneNumber())
                    .bankAccountNumber(request.getBankAccountNumber())
                    .bankCode(request.getBankCode())
                    .build();

            return parentRepository.save(parent);
        }

    private String generateStudentId(String branchCode) {
        // Logic: BRANCH_CODE / YEAR / RANDOM_4_DIGITS
        // Example: GRN-IKJ/2024/4921
        int randomNum = 1000 + new Random().nextInt(9000);
        return branchCode + "/" + Year.now().getValue() + "/" + randomNum;
    }
}
