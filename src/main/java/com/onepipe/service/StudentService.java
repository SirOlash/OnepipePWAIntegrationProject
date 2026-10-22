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
import com.onepipe.config.SchoolFeeSchedule;
import com.onepipe.validation.PaymentInputValidation;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import lombok.RequiredArgsConstructor;
import com.onepipe.security.ResourceAuthorization;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import com.onepipe.exceptions.customExceptions.ResourceNotFoundException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Year;
import java.util.List;
import java.util.Random;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
    private final ResourceAuthorization authorization;
    private final PaymentCalculator calculator;
    private final SchoolFeeSchedule schoolFees;
    private final PaymentInputValidation inputValidation;

    @Transactional
    public RegisterStudentResponse registerStudent(RegisterStudentRequest request) {

        Parent parent = resolveRegistrationParent(request);
        inputValidation.validate(request);
        BigDecimal officialFee = schoolFees.validateSubmittedAmount(request.getClassGrade(), request.getAmount(), calculator);
        calculator.validatePlan(request.getPaymentType(), request.getFrequency(), request.getNumberOfInstallments());
        if (request.getPaymentType() == PaymentType.INSTALLMENT) {
            calculator.installments(officialFee, request.getNumberOfInstallments());
        }
        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(ResourceNotFoundException::new);

        if (request.getPaymentType() != PaymentType.SINGLE_PAYMENT) {
            if ((parent == null && (blank(request.getBankAccountNumber()) || blank(request.getBankCode())))
                    || (parent != null && (blank(parent.getBankAccountNumber()) || blank(parent.getBankCode())))) {
                throw new InvalidPaymentException("Bank details are required for Installment/Subscription");
            }
        }

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
                .installmentFrequency(request.getPaymentType() == PaymentType.SINGLE_PAYMENT ? null : request.getFrequency())
                .numberOfInstallments(request.getPaymentType() == PaymentType.INSTALLMENT ? request.getNumberOfInstallments() : null)
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
                        .terms(paymentService.paymentTerms(payment))
                        .build())
                .build();
    }

    private Parent createSafeParent(RegisterStudentRequest request) {
        // The identity preflight rejected all existing users and phone numbers.
        User user = User.builder()
                    .email(request.getParentEmail().trim().toLowerCase(Locale.ROOT))
                    .password(passwordEncoder.encode(request.getParentPassword()))
                    .role(Role.PARENT)
                    .build();
            user = userRepository.save(user);
            Parent parent = Parent.builder()
                    .user(user)
                    .title(request.getParentTitle())
                    .firstName(request.getParentFirstName())
                    .surname(request.getParentSurname())
                    .phoneNumber(request.getParentPhoneNumber().trim())
                    .bankAccountNumber(request.getBankAccountNumber())
                    .bankCode(request.getBankCode())
                    .build();

            return parentRepository.save(parent);
        }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private Parent resolveRegistrationParent(RegisterStudentRequest request) {
        if (request.getParentEmail() == null || request.getParentEmail().isBlank()
                || request.getParentPhoneNumber() == null || request.getParentPhoneNumber().isBlank()) {
            throw new InvalidPaymentException("Parent email and phone are required");
        }
        Optional<User> caller = authorization.currentUserIfAuthenticated();
        if (caller.isPresent() && caller.get().getRole() == Role.BRANCH_ADMIN) {
            authorization.branch(request.getBranchId());
        }
        if (caller.isPresent() && caller.get().getRole() == Role.PARENT) {
            Parent own = authorization.ownParent();
            if (!caller.get().getEmail().equalsIgnoreCase(request.getParentEmail().trim())
                    || !Objects.equals(own.getPhoneNumber(), request.getParentPhoneNumber().trim())) {
                throw new AccessDeniedException("Access denied");
            }
            if ((request.getBankAccountNumber() != null
                    && !Objects.equals(request.getBankAccountNumber(), own.getBankAccountNumber()))
                    || (request.getBankCode() != null && !Objects.equals(request.getBankCode(), own.getBankCode()))) {
                throw new ResourceConflictException("Registration does not replace existing parent banking details");
            }
            return own;
        }
        try {
            if (userRepository.findByEmailIgnoreCase(request.getParentEmail().trim()).isPresent()
                    || parentRepository.findByPhoneNumber(request.getParentPhoneNumber().trim()).isPresent()) {
                throw new ResourceConflictException(
                        "Registration identity conflicts with an existing account; existing parents must sign in");
            }
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw new ResourceConflictException("Registration identity conflicts with existing data");
        }
        if (request.getParentPassword() == null || request.getParentPassword().isBlank()) {
            throw new InvalidPaymentException("A private password is required for a new parent");
        }
        return null;
    }

    private String generateStudentId(String branchCode) {
        // Logic: BRANCH_CODE / YEAR / RANDOM_4_DIGITS
        // Example: GRN-IKJ/2024/4921
        int randomNum = 1000 + new Random().nextInt(9000);
        return branchCode + "/" + Year.now().getValue() + "/" + randomNum;
    }

    @Transactional(readOnly = true)
    public List<BranchStudentDto> getStudentsByBranch(Long branchId) {
        Branch branch = authorization.branch(branchId);

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

    @Transactional(readOnly = true)
    public List<ParentStudentDto> getStudentsByParentEmail(String parentEmail) {
        return authorization.studentsForParentEmail(parentEmail).stream()
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
