package com.onepipe.service;

import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OnepipeClient onePipeClient;
    private final StudentRepository studentRepository;

    // --- 1. Called when a Student Registers ---
    @Transactional
    public Payment createInitialSchoolFeesPayment(Student student) {
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

        // Generate Refs
        String requestRef = UUID.randomUUID().toString();
        String transactionRef = "SCH-" + System.currentTimeMillis();

        builder.requestRef(requestRef);
        builder.transactionRef(transactionRef);

        // Handle Installment Logic (20% Down)
        if (student.getPreferredPaymentType() == PaymentType.INSTALLMENT) {
            BigDecimal down = totalFee.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
            Integer installments = student.getNumberOfInstallments();

            if (installments == null || installments <= 0) {
                // Default to 3 if missing (Safeguard)
                installments = 3;
            }

            BigDecimal remaining = totalFee.subtract(down);
            BigDecimal perCycle = remaining.divide(new BigDecimal(installments), 2, RoundingMode.HALF_UP);

            builder.downPaymentAmount(down);
            builder.amountPerCycle(perCycle);
            builder.installmentFrequency(student.getInstallmentFrequency());
            builder.numberOfPayments(installments);
        }

        Payment payment = builder.build();
        payment = paymentRepository.save(payment);

        // --- Trigger OnePipe (Mock or Real) ---
        initiatePaymentRequest(payment);

        return payment;
    }

    @Transactional
    public Payment triggerNewPayment(Long studentId,
                                     PaymentCategory category,
                                     BigDecimal amount,
                                     PaymentType paymentType,
                                     String description) {

        Student student = studentRepository.findById(studentId).orElseThrow();
        if (description == null || description.isEmpty()) {
            description = category.name() + " payment for student " + student.getFirstName();
        }

        Payment.PaymentBuilder builder = Payment.builder()
                .student(student)
                .parent(student.getParent())
                .branch(student.getBranch())
                .category(category)
                .paymentType(paymentType)
                .status(PaymentStatus.PENDING)
                .totalAmount(amount)
                .completedPayments(0)
                .description(description);

        // Generate unique references
        builder.requestRef(UUID.randomUUID().toString());
        builder.transactionRef(category.name() + "-" + System.currentTimeMillis());

        // Handle installment logic if needed
        if (paymentType == PaymentType.INSTALLMENT) {
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
        }

        Payment payment = builder.build();
        payment = paymentRepository.save(payment);

        // Trigger OnePipe invoice
        initiatePaymentRequest(payment);

        return payment;
    }


    // --- 2. Centralized Method to Call API ---
    public void initiatePaymentRequest(Payment payment) {

        boolean isDemoMode = true; // TOGGLE THIS FOR DEMO

        OnePipeInvoiceRequest request = new OnePipeInvoiceRequest();
        request.setRequestRef(payment.getRequestRef());

        OnePipeInvoiceRequest.Auth auth = new OnePipeInvoiceRequest.Auth();
        if (payment.getPaymentType() == PaymentType.SINGLE_PAYMENT) {
            auth.setType(null);
            auth.setSecure(null);
        } else {
            auth.setType("bank.account");
            auth.setSecure(EncryptionUtil.encryptTripleDES(
                    payment.getParent().getBankAccountNumber() + ";" + payment.getParent().getBankCode(),
                    "yourSecretKey"
            ));
        }
        request.setAuth(auth);

        // --- Transaction ---
        OnePipeInvoiceRequest.Transaction tx = new OnePipeInvoiceRequest.Transaction();
        tx.setTransactionRef(payment.getTransactionRef());
        tx.setTransactionDesc(payment.getDescription());
        tx.setTransactionRefParent(null);
        if (isDemoMode) {
            tx.setAmount(new BigDecimal("10000.00"));
        } else {
            tx.setAmount(payment.getTotalAmount());
        }


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
                meta.setExpiresIn(30);
                meta.setSkipMessaging(false);
                break;
            case INSTALLMENT:
                meta.setType("instalment");
                if (isDemoMode) {
                    meta.setDownPayment(new BigDecimal("2000.00"));
                    meta.setRepeatFrequency("daily");
                } else {
                    meta.setDownPayment(payment.getDownPaymentAmount());
                    meta.setRepeatFrequency(payment.getInstallmentFrequency().name().toLowerCase());
                }
                meta.setRepeatStartDate(calculateStartDate(payment));
                meta.setNumberOfPayments(payment.getNumberOfPayments());
                break;
            case SUBSCRIPTION:
                meta.setType("subscription");
                if (isDemoMode) {
                    meta.setRepeatFrequency("daily");
                } else {
                    meta.setRepeatFrequency(payment.getInstallmentFrequency().name().toLowerCase());
                }
                meta.setRepeatStartDate(calculateStartDate(payment));
                meta.setRepeatEndDate(calculateEndDate(payment));
                break;
        }
        tx.setMeta(meta);

        request.setTransaction(tx);

        OnePipeResponse response = onePipeClient.sendInvoice(request);

        payment.setVirtualAccountNumber(response.getVirtualAccountNumber());
        payment.setOnePipePaymentId(response.getPaymentId());
        paymentRepository.save(payment);
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

    private String calculateEndDate(Payment payment) {
        LocalDateTime start = LocalDateTime.now();

        switch (payment.getInstallmentFrequency()) {
            case DAILY:
                return formatDate(start.plusDays(payment.getNumberOfPayments()));
            case WEEKLY:
                return formatDate(start.plusWeeks(payment.getNumberOfPayments()));
            case MONTHLY:
                return formatDate(start.plusMonths(payment.getNumberOfPayments()));
            default:
                return formatDate(start.plusDays(payment.getNumberOfPayments()));
        }
    }
}