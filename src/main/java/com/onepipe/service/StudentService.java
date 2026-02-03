package com.onepipe.service;


import com.onepipe.data.entities.*;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.dtos.request.RegisterStudentRequest;
import com.onepipe.dtos.response.BranchStudentDto;
import com.onepipe.dtos.response.ParentStudentDto;
import com.onepipe.dtos.response.RegisterStudentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

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

        Payment payment = paymentService.createInitialSchoolFeesPayment(student);

        return RegisterStudentResponse.builder()
                .studentRegId(regId)
                .studentName(student.getFirstName() + " " + student.getSurname())
                .parentName(parent.getFirstName() + " " + parent.getSurname())
                .message("Registration Successful! Check email for details.")

                .paymentDetails(RegisterStudentResponse.PaymentDetails.builder()
                        .onePipePaymentId(payment.getOnePipePaymentId())
                        .amount(payment.getTotalAmount())
                        .downPayment(payment.getDownPaymentAmount())
                        .bankName(payment.getVirtualAccountBankName())
                        .accountName(payment.getVirtualAccountName())
                        .accountNumber(payment.getVirtualAccountNumber())
                        .paymentType(payment.getPaymentType().name())
                        .expiryDate(payment.getVirtualAccountExpiryDate())
                        .customerAccountNumber(payment.getCustomerAccountNumber())
                        .qrCodeImage(payment.getVirtualAccountQrCodeUrl())
                        .build())
                .build();
    }

    private Parent createSafeParent(RegisterStudentRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.getParentEmail()).orElse(null);
        if (user == null) {
            user = User.builder()
                    .email(request.getParentEmail().toLowerCase())
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

    public List<BranchStudentDto> getStudentsByBranch(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        return studentRepository.findByBranch(branch).stream()
                .map(student -> {
                    List<Payment> payments = paymentService.getPaymentsForStudent(student);

                    String status = determineStudentStatus(payments);
                    return BranchStudentDto.builder()
                            .id(student.getId())
                            .firstName(student.getFirstName())
                            .surname(student.getSurname())
                            .classGrade(student.getClassGrade())
                            .parentFullName(student.getParent().getTitle() + " " + student.getParent().getFirstName() + " " + student.getParent().getSurname())
                            .parentEmail(student.getParent().getUser().getEmail())
                            .paymentType(student.getPreferredPaymentType())
                            .status(status)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private String determineStudentStatus(List<Payment> payments) {
        // Filter for the main School Fees payment
        return payments.stream()
                .filter(p -> p.getCategory() == PaymentCategory.SCHOOL_FEES)
                .findFirst()
                .map(p -> p.getStatus().name()) // Returns PENDING, ACTIVE, SUCCESSFUL, etc.
                .orElse("NO PAYMENT");
    }

    public List<ParentStudentDto> getStudentsByParentEmail(String parentEmail) {
        Parent parent = parentRepository.findByUser_Email(parentEmail)
                .orElseThrow(() -> new RuntimeException("Parent not found with email: " + parentEmail));

        return studentRepository.findByParent(parent).stream()
                .map(student -> {
                    // 1. Get School Fees Payment
                    Payment feePayment = paymentService.getSchoolFeesPayment(student);

                    String status = "NO RECORD";
                    BigDecimal pending = BigDecimal.ZERO;

                    if (feePayment != null) {
                        status = feePayment.getStatus().name();
                        pending = paymentService.calculatePendingAmount(feePayment);
                    }

                    return ParentStudentDto.builder()
                            .id(student.getId())
                            .firstName(student.getFirstName())
                            .surname(student.getSurname())
                            .classGrade(student.getClassGrade())
                            .branchName(student.getBranch().getBusinessName())
//                            .status(status)
                            .pendingAmount(pending)
                            .build();
                })
                .collect(Collectors.toList());
    }


}
