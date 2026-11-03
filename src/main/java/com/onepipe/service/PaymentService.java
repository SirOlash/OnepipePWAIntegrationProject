package com.onepipe.service;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.config.SchoolFeeSchedule;
import com.onepipe.dtos.request.PaymentRequest;
import com.onepipe.dtos.response.PaymentTerms;
import com.onepipe.validation.PaymentInputValidation;
import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.dtos.request.SwitchPlanRequest;
import com.onepipe.dtos.response.BranchPaymentDto;
import com.onepipe.dtos.response.ParentPaymentDto;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeQueryResponse;
import com.onepipe.integration.dto.OnePipeResponse;
import lombok.RequiredArgsConstructor;
import com.onepipe.security.ResourceAuthorization;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OnepipeClient onePipeClient;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final BranchRepository branchRepository;
    private final ResourceAuthorization authorization;
    private final PaymentCalculator calculator;
    private final SchoolFeeSchedule schoolFees;
    private final PaymentInputValidation inputValidation;
    private final PaymentAccounting accounting;
    private final PaymentSchedule schedule;
    private final PaymentLifecycleService lifecycle;
    private final PaymentReconciliationService reconciliation;

    // --- 1. Called when a Student Registers ---
    @Transactional
    Payment createInitialSchoolFeesPayment(Student student) {
        // Internal registration path: StudentService has verified a new or owned parent.
        validateNoExistingSchoolFeesPayment(student);

        BigDecimal totalFee = schoolFees.feeFor(student.getClassGrade());

        Payment.PaymentBuilder builder = Payment.builder()
                .student(student)
                .parent(student.getParent())
                .branch(student.getBranch())
                .category(PaymentCategory.SCHOOL_FEES)
                .paymentType(student.getPreferredPaymentType())
                .status(PaymentStatus.PENDING)
                .totalAmount(totalFee)
                .completedPayments(0)
                .accountingVersion(1)
                .billingAnchor(schedule.anchorNow())
                .description("School Fees for " + student.getClassGrade().name());

        String requestRef = UUID.randomUUID().toString();
        String transactionRef = "SCH-" + UUID.randomUUID();

        builder.requestRef(requestRef);
        builder.transactionRef(transactionRef);

        // ✅ HANDLE ALL PAYMENT TYPES
        configurePaymentDetails(builder, student.getPreferredPaymentType(), totalFee,
                student.getInstallmentFrequency(), student.getNumberOfInstallments());

        Payment payment = builder.build();
        paymentRepository.save(payment);

        return initiatePaymentRequest(payment);
    }

    // --- UPDATED triggerNewPayment ---
    @Transactional
    public RegisterStudentResponse triggerNewPayment(Long studentId,
                                                     PaymentCategory category,
                                                     BigDecimal amount,
                                                     PaymentType paymentType,
                                                     String description) {
        PaymentRequest request = new PaymentRequest();
        request.setStudentId(studentId);
        request.setCategory(category);
        request.setAmount(amount);
        request.setPaymentType(paymentType);
        request.setDescription(description);
        return triggerNewPayment(request);
    }

    @Transactional
    public RegisterStudentResponse triggerNewPayment(PaymentRequest request) {
        Student student = authorization.student(request.getStudentId());
        inputValidation.validate(request);
        PaymentCategory category = request.getCategory();
        String description = request.getDescription();
        PaymentType effectivePaymentType = request.getPaymentType();
        InstallmentFrequency frequency = request.getFrequency() != null
                ? request.getFrequency() : student.getInstallmentFrequency();
        Integer count = request.getNumberOfInstallments() != null
                ? request.getNumberOfInstallments() : student.getNumberOfInstallments();

        BigDecimal amount;
        if (category == PaymentCategory.SCHOOL_FEES) {
            validateNoExistingSchoolFeesPayment(student);
            amount = schoolFees.validateSubmittedAmount(student.getClassGrade(), request.getAmount(), calculator);
            effectivePaymentType = student.getPreferredPaymentType();
            if (request.getPaymentType() != effectivePaymentType
                    || (request.getFrequency() != null && request.getFrequency() != student.getInstallmentFrequency())
                    || (request.getNumberOfInstallments() != null
                        && !request.getNumberOfInstallments().equals(student.getNumberOfInstallments()))) {
                throw new InvalidPaymentException("School fee payment terms must match the student's saved plan");
            }
            frequency = student.getInstallmentFrequency();
            count = student.getNumberOfInstallments();
        } else {
            amount = calculator.money(request.getAmount());
        }
        if (description == null || description.isEmpty()) {
            description = category.name() + " payment for student " + student.getFirstName();
        }
        Payment.PaymentBuilder builder = Payment.builder()
                .student(student)
                .parent(student.getParent())
                .branch(student.getBranch())
                .category(category)
                .paymentType(effectivePaymentType)
                .status(PaymentStatus.PENDING)
                .totalAmount(amount)
                .completedPayments(0)
                .accountingVersion(1)
                .billingAnchor(schedule.anchorNow())
                .description(description);

        // Generate unique references
        builder.requestRef(UUID.randomUUID().toString());
        builder.transactionRef(category.name() + "-" + UUID.randomUUID());

        // ✅ HANDLE ALL PAYMENT TYPES
        configurePaymentDetails(builder, effectivePaymentType, amount, frequency, count);

        Payment payment = builder.build();
        payment = paymentRepository.save(payment);

        // Trigger OnePipe invoice
        Payment completedPayment = initiatePaymentRequest(payment);

        return RegisterStudentResponse.builder()
                .studentRegId(student.getStudentRegId())
                .studentName(student.getFirstName() + " " + student.getSurname())
                .parentName(student.getParent().getFirstName() + " " + student.getParent().getSurname())
                .message("Invoice successfully created!!! Check email for details.")
                .paymentDetails(RegisterStudentResponse.PaymentDetails.builder()
                        .onePipePaymentId(completedPayment.getOnePipePaymentId())
                        .amount(completedPayment.getTotalAmount())
                        .downPayment(completedPayment.getDownPaymentAmount())
                        .bankName(completedPayment.getVirtualAccountBankName())
                        .accountName(completedPayment.getVirtualAccountName())
                        .accountNumber(completedPayment.getVirtualAccountNumber())
                        .paymentType(completedPayment.getPaymentType().name())
                        .expiryDate(completedPayment.getVirtualAccountExpiryDate())
                        .customerAccountNumber(completedPayment.getCustomerAccountNumber())
                        .qrCodeImage(completedPayment.getVirtualAccountQrCodeUrl())
                        .terms(paymentTerms(completedPayment))
                        .build())
                .build();
    }

    // ✅ NEW CENTRALIZED METHOD - ADD THIS
    private void configurePaymentDetails(Payment.PaymentBuilder builder,
                                         PaymentType paymentType,
                                         BigDecimal amount,
                                         InstallmentFrequency frequency, Integer installments) {
        calculator.money(amount);
        calculator.validatePlan(paymentType, frequency, installments);
        switch (paymentType) {
            case SINGLE_PAYMENT:
                // No additional configuration needed
                break;

            case INSTALLMENT:
                PaymentCalculator.InstallmentPlan plan = calculator.installments(amount, installments);
                builder.downPaymentAmount(plan.downPayment());
                builder.amountPerCycle(plan.installments().get(0));
                builder.installmentFrequency(frequency);
                builder.numberOfPayments(installments);
                break;

            case SUBSCRIPTION:
                // Unlimited subscription: amount is per cycle, not a finite tuition balance.
                builder.amountPerCycle(amount);
                builder.installmentFrequency(frequency);
                builder.numberOfPayments(null);
                break;

            default:
                throw new IllegalArgumentException("Unsupported payment type: " + paymentType);
        }
    }


    // --- 2. Centralized Method to Call API ---
    private Payment initiatePaymentRequest(Payment payment) {

        OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();
        request.setRequestRef(payment.getRequestRef());

        // --- Transaction ---
        OnePipeInvoiceRequest.Transaction tx = new OnePipeInvoiceRequest.Transaction();
        tx.setTransactionRef(payment.getTransactionRef());
        tx.setTransactionDesc(payment.getDescription());
        tx.setTransactionRefParent(null);

        tx.setAmount(calculator.toKobo(payment.getTotalAmount()));


        // --- Customer ---
        OnePipeInvoiceRequest.Customer customer = new OnePipeInvoiceRequest.Customer();
        customer.setCustomerRef(payment.getParent().getPhoneNumber());
        customer.setFirstname(payment.getParent().getFirstName());
        customer.setSurname(payment.getParent().getSurname());
        customer.setEmail(payment.getParent().getUser().getEmail());
        customer.setMobileNo(payment.getParent().getPhoneNumber());
        tx.setCustomer(customer);

        // --- Meta ---
        OnePipeInvoiceRequest.Meta meta = new OnePipeInvoiceRequest.Meta();
        meta.setBillerCode(payment.getBranch().getBillerCode());

        switch (payment.getPaymentType()) {
            case SINGLE_PAYMENT:
                meta.setType("single_payment");
                meta.setExpiresIn(60);
                meta.setSkipMessaging(false);
                break;
            case INSTALLMENT:
                meta.setType("instalment");
                meta.setDownPayment(calculator.toKobo(payment.getDownPaymentAmount()));
                meta.setRepeatFrequency(payment.getInstallmentFrequency().name().toLowerCase(Locale.ROOT));
                meta.setRepeatStartDate(calculateStartDate(payment));
                meta.setNumberOfPayments(payment.getNumberOfPayments());
                break;
            case SUBSCRIPTION:
                meta.setType("subscription");
                meta.setRepeatFrequency(payment.getInstallmentFrequency().name().toLowerCase(Locale.ROOT));
                meta.setRepeatStartDate(calculateStartDate(payment));
                break;
        }
        tx.setMeta(meta);

        request.setTransaction(tx);


        OnePipeResponse response = onePipeClient.sendInvoice(request, payment);

        payment.setVirtualAccountNumber(response.getVirtualAccountNumber());
        payment.setVirtualAccountName(response.getVirtualAccountName());
        payment.setVirtualAccountBankName(response.getVirtualAccountBankName());
        payment.setVirtualAccountBankCode(response.getVirtualAccountBankCode());
        payment.setVirtualAccountExpiryDate(response.getVirtualAccountExpiryDate());

        payment.setCustomerAccountNumber(response.getCustomerAccountNumber());

        payment.setOnePipePaymentId(response.getPaymentId());
        payment.setVirtualAccountQrCodeUrl(response.getVirtualAccountQrCodeUrl());
        paymentRepository.save(payment);

        return payment;
    }


    private String formatDate(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        return dateTime.format(formatter);
    }

    private String calculateStartDate(Payment payment) {
        return formatDate(firstBillingDate(payment));
    }

    private LocalDateTime firstBillingDate(Payment payment) {
        return schedule.firstBillingDate(payment);
    }

    public PaymentTerms paymentTerms(Payment payment) {
        List<BigDecimal> schedule = List.of();
        if (payment.getPaymentType() == PaymentType.INSTALLMENT) {
            try {
                schedule = calculator.installments(payment.getTotalAmount(), payment.getDownPaymentAmount(),
                        payment.getNumberOfPayments()).installments();
            } catch (InvalidPaymentException ex) {
                // Legacy malformed schedules are not reconstructed using invented defaults.
                schedule = List.of();
            }
        }
        boolean recurring = payment.getPaymentType() != PaymentType.SINGLE_PAYMENT;
        LocalDateTime start = recurring && payment.getInstallmentFrequency() != null
                && payment.getBillingAnchor() != null ? firstBillingDate(payment) : null;
        PaymentAccounting.Progress progress = accounting.snapshot(payment);
        BigDecimal remaining = progress.remainingAmount();
        String basis = progress.balanceBasis();
        return new PaymentTerms("NGN", payment.getAmountPerCycle(), schedule,
                payment.getInstallmentFrequency(), start, null, remaining, basis, progress.confirmedAmount());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancelSubscription(String onePipeId) {
        lifecycle.cancelByProviderId(onePipeId);
    }

    @Transactional(readOnly = true)
    public List<BranchPaymentDto> getPaymentsByBranch(Long branchId) {
        Branch branch = authorization.branch(branchId);
        List<Payment> records = paymentRepository.findByBranch(branch);
        authorization.checkPayments(records);

        // Define date format for the UI
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        return records.stream()
                .map(payment -> BranchPaymentDto.builder()
                        .id(payment.getId())
                        .studentName(payment.getStudent() != null
                                ? payment.getStudent().getFirstName() + " " + payment.getStudent().getSurname()
                                : "N/A")
                        .description(payment.getDescription())
                        .amount(payment.getTotalAmount())
                        .remainingAmount(calculatePendingAmount(payment))
                        .numberOfPayments(payment.getNumberOfPayments())
                        .completedPayments(payment.getCompletedPayments())
                        .terms(paymentTerms(payment))
                        .type(payment.getPaymentType())
                        .status(payment.getStatus())
                        .date(payment.getCreatedAt().format(formatter))
                        .build())
                .collect(Collectors.toList());
    }

    List<Payment> getPaymentsForStudent(Student student) {
        Student trustedStudent = authorization.student(student.getId());
        List<Payment> records = paymentRepository.findByStudent(trustedStudent);
        authorization.checkPayments(records);
        return records;
    }

    Payment getSchoolFeesPayment(Student student) {
        return getPaymentsForStudent(student).stream()
                .filter(p -> p.getCategory() == PaymentCategory.SCHOOL_FEES)
                .findFirst()
                .orElse(null);
    }

    public BigDecimal calculatePendingAmount(Payment payment) {
        return accounting.snapshot(payment).remainingAmount();
    }

    @Transactional(readOnly = true)
    public List<ParentPaymentDto> getPaymentsForChild(Long studentId) {
        Student student = authorization.student(studentId);
        List<Payment> records = paymentRepository.findByStudent(student);
        authorization.checkPayments(records);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        return records.stream()
                .map(p -> ParentPaymentDto.builder()
                        .onePipePaymentId(p.getOnePipePaymentId())
                        .description(p.getDescription())
                        .amount(p.getTotalAmount())
                        .downPayment(p.getDownPaymentAmount())
                        .customerAccountNumber(p.getCustomerAccountNumber())
                        .status(p.getStatus())
                        .paymentType(p.getPaymentType())
                        .date(p.getCreatedAt().format(formatter))
                        .remainingAmount(calculatePendingAmount(p))
                        .numberOfPayments(p.getNumberOfPayments())
                        .completedPayments(p.getCompletedPayments())
                        .terms(paymentTerms(p))
                        // Banking Details
                        .virtualAccountNumber(p.getVirtualAccountNumber())
                        .virtualAccountBankName(p.getVirtualAccountBankName())
                        .virtualAccountName(p.getVirtualAccountName())
                        .virtualAccountExpiryDate(p.getVirtualAccountExpiryDate())
                        .qrCodeUrl(p.getVirtualAccountQrCodeUrl())
                        .build())
                .collect(Collectors.toList());
    }

    @Scheduled(fixedRate = 60000)
    public void expireOverduePayments() {
        lifecycle.expireAll();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OnePipeQueryResponse queryAndFixPaymentStatus(String onePipeId) {
        return reconciliation.query(onePipeId);
    }

    private void validateNoExistingSchoolFeesPayment(Student student) {
        List<PaymentStatus> ongoingStatuses = Arrays.asList(PaymentStatus.ACTIVE, PaymentStatus.PENDING);

        Optional<Payment> existingPayment = paymentRepository.findFirstByStudentAndCategoryAndStatusIn(
                student,
                PaymentCategory.SCHOOL_FEES,
                ongoingStatuses
        );

        if (existingPayment.isPresent()) {
            throw new ResourceConflictException("An ongoing school fees payment prevents this operation");
        }
    }

        @Transactional
        public void updatePaymentPlan(Long studentId, SwitchPlanRequest request) {
            Student student = authorization.ownStudent(studentId);
            inputValidation.validate(request);
            calculator.validatePlan(request.getNewPaymentType(), request.getFrequency(), request.getNumberOfInstallments());
            if (request.getNewPaymentType() == PaymentType.INSTALLMENT) {
                calculator.installments(schoolFees.feeFor(student.getClassGrade()), request.getNumberOfInstallments());
            }

            boolean hasActiveOrPending = paymentRepository.findByStudent(student).stream()
                    .filter(p -> p.getCategory() == PaymentCategory.SCHOOL_FEES)
                    .anyMatch(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.ACTIVE);

            if (hasActiveOrPending) {
                throw new ResourceConflictException("An ongoing school fees payment prevents this operation");
            }

            if (request.getNewPaymentType() == PaymentType.INSTALLMENT ||
                    request.getNewPaymentType() == PaymentType.SUBSCRIPTION) {

                boolean suppliedAccount = request.getBankAccountNumber() != null;
                boolean suppliedCode = request.getBankCode() != null;
                if (suppliedAccount != suppliedCode || (suppliedAccount && (request.getBankAccountNumber().isBlank()
                        || request.getBankCode().isBlank()))) {
                    throw new InvalidPaymentException("Both nonblank banking fields are required");
                }
                if (suppliedAccount) {
                    Parent parent = student.getParent();
                    parent.setBankAccountNumber(request.getBankAccountNumber());
                    parent.setBankCode(request.getBankCode());

                    parentRepository.save(parent);
                } else {
                    if (student.getParent().getBankAccountNumber() == null || student.getParent().getBankAccountNumber().isBlank()
                            || student.getParent().getBankCode() == null || student.getParent().getBankCode().isBlank()) {
                        throw new InvalidPaymentException("Bank details are required to switch to this plan");
                    }
                }
            }
            student.setPreferredPaymentType(request.getNewPaymentType());

            if (request.getNewPaymentType() == PaymentType.INSTALLMENT) {
                student.setInstallmentFrequency(request.getFrequency());
                student.setNumberOfInstallments(request.getNumberOfInstallments());
            } else if (request.getNewPaymentType() == PaymentType.SUBSCRIPTION) {
                student.setInstallmentFrequency(request.getFrequency());
                student.setNumberOfInstallments(null);
            } else {
                student.setInstallmentFrequency(null);
                student.setNumberOfInstallments(null);
            }
            studentRepository.save(student);
        }
}
