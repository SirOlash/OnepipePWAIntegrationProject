package com.onepipe.service;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.PaymentCategory;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final List<PaymentStatus> ACTIVE_PAYMENT_STATUSES = List.of(
            PaymentStatus.PENDING,
            PaymentStatus.ACTIVE
    );

    // --- 1. Called when a Student Registers ---
    @Transactional
    Payment createInitialSchoolFeesPayment(Student student) {
        // Internal registration path: StudentService has verified a new or owned parent.
        validateNoExistingSchoolFeesPayment(student);

        BigDecimal totalFee = student.getClassGrade().getDefaultFee();

        Payment.PaymentBuilder builder = Payment.builder()
                .student(student)
                .parent(student.getParent())
                .branch(student.getBranch())
                .category(PaymentCategory.SCHOOL_FEES)
                .paymentType(student.getPreferredPaymentType())
                .status(PaymentStatus.PENDING)
                .totalAmount(totalFee)
                .completedPayments(0)
                .description("School Fees for " + student.getClassGrade().name());

        String requestRef = UUID.randomUUID().toString();
        String transactionRef = "SCH-" + UUID.randomUUID();

        builder.requestRef(requestRef);
        builder.transactionRef(transactionRef);

        // ✅ HANDLE ALL PAYMENT TYPES
        configurePaymentDetails(builder, student.getPreferredPaymentType(), totalFee, student);

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

        Student student = authorization.student(studentId);

        if (category == PaymentCategory.SCHOOL_FEES) {
            validateNoExistingSchoolFeesPayment(student);
        }
        if (description == null || description.isEmpty()) {
            description = category.name() + " payment for student " + student.getFirstName();
        }
        PaymentType effectivePaymentType = paymentType;
        if (PaymentCategory.SCHOOL_FEES.equals(category)) {
            effectivePaymentType = student.getPreferredPaymentType();
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
                .description(description);

        // Generate unique references
        builder.requestRef(UUID.randomUUID().toString());
        builder.transactionRef(category.name() + "-" + UUID.randomUUID());

        // ✅ HANDLE ALL PAYMENT TYPES
        configurePaymentDetails(builder,effectivePaymentType, amount, student);

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
                        .amount(completedPayment.getTotalAmount())
                        .downPayment(completedPayment.getDownPaymentAmount())
                        .bankName(completedPayment.getVirtualAccountBankName())
                        .accountName(completedPayment.getVirtualAccountName())
                        .accountNumber(completedPayment.getVirtualAccountNumber())
                        .paymentType(completedPayment.getPaymentType().name())
                        .expiryDate(completedPayment.getVirtualAccountExpiryDate())
                        .customerAccountNumber(completedPayment.getCustomerAccountNumber())
                        .qrCodeImage(completedPayment.getVirtualAccountQrCodeUrl())
                        .build())
                .build();
    }

    // ✅ NEW CENTRALIZED METHOD - ADD THIS
    private void configurePaymentDetails(Payment.PaymentBuilder builder,
                                         PaymentType paymentType,
                                         BigDecimal amount,
                                         Student student) {
        switch (paymentType) {
            case SINGLE_PAYMENT:
                // No additional configuration needed
                break;

            case INSTALLMENT:
                BigDecimal down = amount.multiply(new BigDecimal("0.20"))
                        .setScale(2, RoundingMode.HALF_UP);
                Integer installments = student.getNumberOfInstallments();

                if (installments == null || installments <= 0) {
                    installments = 3; // default safeguard
                }

                BigDecimal remaining = amount.subtract(down);
                BigDecimal perCycle = remaining.divide(new BigDecimal(installments), 2, RoundingMode.HALF_UP);

                builder.downPaymentAmount(down);
                builder.amountPerCycle(perCycle);
                builder.installmentFrequency(student.getInstallmentFrequency());
                builder.numberOfPayments(installments);
                break;

            case SUBSCRIPTION:
                // For subscriptions, set the billing cycle
                // Amount per cycle = total amount (this is the recurring amount)
//                builder.downPaymentAmount(amount);
                builder.amountPerCycle(amount);
                builder.installmentFrequency(student.getInstallmentFrequency() != null
                        ? student.getInstallmentFrequency()
                        : com.onepipe.data.enums.InstallmentFrequency.MONTHLY); // Default to monthly
                // For subscriptions, numberOfPayments can be null (unlimited) or set a limit
                builder.numberOfPayments(null); // Unlimited subscription
                break;

            default:
                throw new IllegalArgumentException("Unsupported payment type: " + paymentType);
        }
    }


    // --- 2. Centralized Method to Call API ---
    private Payment initiatePaymentRequest(Payment payment) {

        boolean isDemoMode = true; // Legacy schedule/pricing behavior; provider selection uses onepipe.mode.

        OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();
        request.setRequestRef(payment.getRequestRef());

        // --- Transaction ---
        OnePipeInvoiceRequest.Transaction tx = new OnePipeInvoiceRequest.Transaction();
        tx.setTransactionRef(payment.getTransactionRef());
        tx.setTransactionDesc(payment.getDescription());
        tx.setTransactionRefParent(null);

//        tx.setDetails(new HashMap<>());

        BigDecimal amountInKobo = payment.getTotalAmount().multiply(new BigDecimal("100"));
        tx.setAmount(amountInKobo.setScale(0, RoundingMode.HALF_UP));


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
                if (isDemoMode) {
                    meta.setDownPayment(new BigDecimal("10000"));
                    meta.setRepeatFrequency("daily");
                    meta.setExpiresIn(60);
                } else {
                    meta.setDownPayment(payment.getDownPaymentAmount().multiply(new BigDecimal("100")));
                    meta.setRepeatFrequency(payment.getInstallmentFrequency().name().toLowerCase());
                }
                meta.setRepeatStartDate(calculateStartDate(payment));
                meta.setNumberOfPayments(payment.getNumberOfPayments());
                break;
            case SUBSCRIPTION:
                meta.setType("subscription");
                if (isDemoMode) {
//                    BigDecimal downPayment = payment.getDownPaymentAmount() != null
//                            ? payment.getDownPaymentAmount().multiply(new BigDecimal("100"))
//                            : payment.getTotalAmount().multiply(new BigDecimal("100"));
//                    meta.setDownPayment(downPayment);
                    meta.setRepeatFrequency("daily");
                    meta.setRepeatStartDate(formatDate(LocalDateTime.now().plusDays(1)));
                    meta.setRepeatEndDate(formatDate(LocalDateTime.now().plusDays(3)));
//                    meta.setExpiresIn(60);
                } else {
                    BigDecimal downPayment = payment.getDownPaymentAmount() != null
                            ? payment.getDownPaymentAmount().multiply(new BigDecimal("100"))
                            : payment.getTotalAmount().multiply(new BigDecimal("100"));
                    meta.setDownPayment(downPayment);
                    meta.setRepeatFrequency(payment.getInstallmentFrequency().name().toLowerCase());
                    meta.setRepeatStartDate(formatDate(LocalDateTime.now().plusMonths(1)));
                    meta.setRepeatEndDate(formatDate(LocalDateTime.now().plusMonths(3)));
                }
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
        LocalDateTime now = LocalDateTime.now();

        switch (payment.getInstallmentFrequency()) {
            case DAILY:
                return formatDate(now.plusDays(1));
            case WEEKLY:
                return formatDate(now.plusWeeks(1));
            case MONTHLY:
                return formatDate(now.plusMonths(1));
            default:
                return formatDate(now.plusDays(1));
        }
    }

    @Transactional
    public void cancelSubscription(String onePipeId) {
        Payment payment = authorization.payment(onePipeId);

        if (payment.getPaymentType() != PaymentType.SUBSCRIPTION) {
            throw new ResourceConflictException("Only subscriptions can be cancelled");
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new ResourceConflictException("Subscription is already cancelled");
        }

        boolean success = onePipeClient.cancelMandate(payment);

        if (success) {
            payment.setStatus(PaymentStatus.CANCELLED);
            paymentRepository.save(payment);
        } else {
            throw new RuntimeException("OnePipe failed to process cancellation");
        }
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
        if (payment.getStatus() == PaymentStatus.SUCCESSFUL) {
            return BigDecimal.ZERO;
        }

        // Single Payment
        if (payment.getPaymentType() == PaymentType.SINGLE_PAYMENT  ||
                payment.getPaymentType() == PaymentType.SUBSCRIPTION) {
            return payment.getTotalAmount();
        }

        if (payment.getStatus() == PaymentStatus.PENDING) {
            return payment.getTotalAmount();
        }

        // Installment
        // Formula: Total - (DownPayment + (PerCycle * CompletedCount))
        BigDecimal down = payment.getDownPaymentAmount() != null ? payment.getDownPaymentAmount() : BigDecimal.ZERO;
        BigDecimal perCycle = payment.getAmountPerCycle() != null ? payment.getAmountPerCycle() : BigDecimal.ZERO;
        int completed = payment.getCompletedPayments() != null ? payment.getCompletedPayments() : 0;

        BigDecimal paidSoFar = down.add(perCycle.multiply(new BigDecimal(completed)));

        // Ensure we don't return negative numbers
        return payment.getTotalAmount().subtract(paidSoFar).max(BigDecimal.ZERO);
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
        // 1. Fetch all PENDING payments
        List<Payment> pendingPayments = paymentRepository.findByStatus(PaymentStatus.PENDING);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();

        for (Payment payment : pendingPayments) {
            String expiryStr = payment.getVirtualAccountExpiryDate();

            // Skip if no expiry date (e.g. manual payments)
            if (expiryStr == null || expiryStr.isEmpty()) {
                continue;
            }

            try {
                // 2. Parse the OnePipe Date String
                // Handle potential format differences (OnePipe sometimes sends different formats)
                // We assume "yyyy-MM-dd HH:mm:ss" based on your logs
                LocalDateTime expiryDate = LocalDateTime.parse(expiryStr, formatter);

                // 3. Check if Expired
                if (expiryDate.isBefore(now)) {
                    System.out.println("⚠️ Expiring Payment ID: " + payment.getId() + " (Time elapsed)");

                    payment.setStatus(PaymentStatus.CANCELLED);
                    paymentRepository.save(payment);
                }
            } catch (Exception e) {
                // Log error but don't stop the loop for other payments
                System.err.println("Error parsing date for payment " + payment.getId() + ": " + expiryStr);
            }
        }
    }

    @Transactional
    public OnePipeQueryResponse queryAndFixPaymentStatus(String onePipeId) {
        Payment payment = authorization.payment(onePipeId);

        String returnMessage;
        // Optimization: If already successful, don't bother querying
        if (payment.getStatus() == PaymentStatus.SUCCESSFUL || payment.getStatus() == PaymentStatus.ACTIVE) {
            returnMessage = "Payment is already marked as successful/active.";
        }

        else {
            // 3. It is PENDING/FAILED locally, so let's ask OnePipe
            OnePipeResponse response = onePipeClient.queryTransaction(payment.getTransactionRef());

            if ("successful".equalsIgnoreCase(response.getStatus())) {
                // 4. OnePipe says it's money! Update our DB.

                if (payment.getPaymentType() == PaymentType.SINGLE_PAYMENT) {
                    payment.setStatus(PaymentStatus.SUCCESSFUL);
                }
                else if (payment.getPaymentType() == PaymentType.INSTALLMENT ||
                        payment.getPaymentType() == PaymentType.SUBSCRIPTION) {
                    // Mark the first payment (down payment) as done
                    payment.setCompletedPayments(1);
                    payment.setStatus(PaymentStatus.ACTIVE);
                }

                // Save the changes
                payment = paymentRepository.save(payment);
                returnMessage = "Payment confirmed successful and updated.";
            } else {
                // OnePipe says it is still pending or failed
                returnMessage = "Payment status checked: " + response.getStatus();
            }
        }

        // 5. Return the Clean DTO (Fixes the ByteBuddy/Serialization error)
        return OnePipeQueryResponse.builder()
                .transactionRef(payment.getTransactionRef())
                .requestRef(payment.getRequestRef())
                .onePipePaymentId(payment.getOnePipePaymentId())
                .status(payment.getStatus().name())
                .message(returnMessage)
                .build();
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

            boolean hasActiveOrPending = paymentRepository.findByStudent(student).stream()
                    .filter(p -> p.getCategory() == PaymentCategory.SCHOOL_FEES)
                    .anyMatch(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.ACTIVE);

            if (hasActiveOrPending) {
                throw new ResourceConflictException("An ongoing school fees payment prevents this operation");
            }

            if (request.getNewPaymentType() == PaymentType.INSTALLMENT ||
                    request.getNewPaymentType() == PaymentType.SUBSCRIPTION) {

                if (request.getBankAccountNumber() != null && request.getBankCode() != null) {
                    Parent parent = student.getParent();
                    parent.setBankAccountNumber(request.getBankAccountNumber());
                    parent.setBankCode(request.getBankCode());

                    parentRepository.save(parent);
                } else {
                    if (student.getParent().getBankAccountNumber() == null) {
                        throw new RuntimeException("Bank Account details are required to switch to this plan.");
                    }
                }
            }
            student.setPreferredPaymentType(request.getNewPaymentType());

            if (request.getNewPaymentType() == PaymentType.INSTALLMENT) {
                student.setInstallmentFrequency(request.getFrequency());
                student.setNumberOfInstallments(request.getNumberOfInstallments());
            } else {
                student.setInstallmentFrequency(null);
                student.setNumberOfInstallments(null);
            }
            studentRepository.save(student);
        }
}
