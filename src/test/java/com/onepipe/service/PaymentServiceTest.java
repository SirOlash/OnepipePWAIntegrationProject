package com.onepipe.service;

import com.onepipe.data.entities.*;
import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OnepipeClient onePipeClient;

    @Mock
    private StudentRepository studentRepository;

    @InjectMocks
    private PaymentService paymentService;

    private Student mockStudent;
    private Parent mockParent;
    private Branch mockBranch;

    @BeforeEach
    void setUp() {
        User parentUser = User.builder().email("parent@test.com").build();

        mockParent = new Parent();
        mockParent.setUser(parentUser);
        mockParent.setFirstName("John");
        mockParent.setSurname("Doe");
        mockParent.setPhoneNumber("08012345678");
        mockParent.setBankAccountNumber("1234567890");
        mockParent.setBankCode("057");

        mockBranch = new Branch();
        mockBranch.setBillerCode("BILLER_001");

        mockStudent = new Student();
        mockStudent.setId(1L);
        mockStudent.setParent(mockParent);
        mockStudent.setBranch(mockBranch);
    }

    @Test
    void testCreateInitialPayment_InstallmentMath() {
        mockStudent.setClassGrade(ClassGrade.JSS1);
        mockStudent.setPreferredPaymentType(PaymentType.INSTALLMENT);
        mockStudent.setNumberOfInstallments(4);
        mockStudent.setInstallmentFrequency(InstallmentFrequency.MONTHLY);

        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        OnePipeResponse mockResponse = new OnePipeResponse();
        mockResponse.setVirtualAccountNumber("VIRT_123");

        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(mockResponse);

        Payment result = paymentService.createInitialSchoolFeesPayment(mockStudent);

        System.out.println("Testing Installment Math...");
        Assertions.assertEquals(0, new BigDecimal("100000.00").compareTo(result.getTotalAmount()));
        Assertions.assertEquals(0, new BigDecimal("20000.00").compareTo(result.getDownPaymentAmount()));
        Assertions.assertEquals(0, new BigDecimal("20000.00").compareTo(result.getAmountPerCycle()));
        Assertions.assertEquals(4, result.getNumberOfPayments());
    }

    @Test
    void testDemoMode_ForcesSmallAmount_And_DailyFrequency() {
        mockStudent.setClassGrade(ClassGrade.SS3); // Fee: 600,000
        mockStudent.setPreferredPaymentType(PaymentType.INSTALLMENT);
        mockStudent.setNumberOfInstallments(3);
        mockStudent.setInstallmentFrequency(InstallmentFrequency.MONTHLY);

        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);
        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(new OnePipeResponse());

        paymentService.createInitialSchoolFeesPayment(mockStudent);

        ArgumentCaptor<OnePipeInvoiceRequest> captor = ArgumentCaptor.forClass(OnePipeInvoiceRequest.class);
        verify(onePipeClient).sendInvoice(captor.capture());

        OnePipeInvoiceRequest requestSent = captor.getValue();

        System.out.println("Testing Demo Mode Overrides...");

        BigDecimal sentAmount = requestSent.getTransaction().getAmount();
        Assertions.assertEquals(0, new BigDecimal("10000.00").compareTo(sentAmount),
                "Expected Demo Amount (10000) but got " + sentAmount);

        String sentFrequency = requestSent.getTransaction().getMeta().getRepeatFrequency();
        Assertions.assertEquals("daily", sentFrequency,
                "Expected Demo Frequency 'daily' but got " + sentFrequency);

        BigDecimal sentDown = requestSent.getTransaction().getMeta().getDownPayment();
        Assertions.assertEquals(0, new BigDecimal("2000.00").compareTo(sentDown));
    }

    @Test
    void testTriggerNewPayment_Adhoc() {
        Long studentId = 1L;
        BigDecimal amount = new BigDecimal("5000.00");
        String desc = "Textbook";

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(mockStudent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(new OnePipeResponse());

        Payment result = paymentService.triggerNewPayment(
                studentId,
                PaymentCategory.ADHOC_PAYMENT,
                amount,
                PaymentType.SINGLE_PAYMENT,
                desc
        );

        Assertions.assertEquals(amount, result.getTotalAmount());
        Assertions.assertEquals(PaymentType.SINGLE_PAYMENT, result.getPaymentType());
        Assertions.assertEquals(desc, result.getDescription());

        verify(onePipeClient, times(1)).sendInvoice(any(OnePipeInvoiceRequest.class));
    }

}


//package com.onepipe.service;
//
//import com.onepipe.data.entities.Branch;
//import com.onepipe.data.entities.Parent;
//import com.onepipe.data.entities.Payment;
//import com.onepipe.data.entities.Student;
//import com.onepipe.data.enums.ClassGrade;
//import com.onepipe.data.enums.InstallmentFrequency;
//import com.onepipe.data.enums.PaymentCategory;
//import com.onepipe.data.enums.PaymentStatus;
//import com.onepipe.data.enums.PaymentType;
//import com.onepipe.data.repositories.PaymentRepository;
//import com.onepipe.data.repositories.StudentRepository;
//import com.onepipe.integration.OnepipeClient;
//
//import com.onepipe.integration.OnepipeClient;
//import com.onepipe.integration.dto.OnePipeInvoiceRequest;
//import com.onepipe.integration.dto.OnePipeResponse;
//
//import com.onepipe.utils.EncryptionUtil; // Import if you need to mock static methods, or for constants
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.Optional;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//import static org.junit.jupiter.api.Assertions.*; // For assertNotNull, etc.
//
//@ExtendWith(MockitoExtension.class)
//class PaymentServiceTest {
//
//    @Mock
//    private PaymentRepository paymentRepository;
//
//    @Mock
//    private OnepipeClient onePipeClient;
//
//    @Mock
//    private StudentRepository studentRepository;
//
//    @InjectMocks
//    private PaymentService paymentService;
//
//    // Define a test secret key for EncryptionUtil for the tests
//    private static final String TEST_TRIPLE_DES_SECRET = "testTripleDesSecretKey123456789"; // 24 chars for 3DES
//
//    @BeforeEach
//    void setUp() {
//        // Mock static method if needed, but for simplicity, we assume the EncryptionUtil
//        // class uses a fixed test key or the key passed is the correct one.
//        // For this test, we will just ensure it's called.
//    }
//
//    // Helper to create a mock student with parent/branch
//    private Student setupMockStudent(PaymentType paymentType, InstallmentFrequency frequency, Integer numInstallments, String parentFirstName, String parentSurname) {
//        Branch branch = Branch.builder().businessName("Greenfield Ikeja").businessShortName("GRN-IKJ").billerCode("000019").build();
//
//        com.onepipe.data.entities.User parentLoginUser = com.onepipe.data.entities.User.builder().email("parent@test.com").password("hashed").build();
//
//        Parent parent = Parent.builder()
////                .id(1L)
//                .firstName(parentFirstName)
//                .surname(parentSurname)
//                .phoneNumber("2348012345678")
//                .bankAccountNumber("1234567890")
//                .bankCode("057")
//                .user(parentLoginUser)
//                .build();
//
//        Student student = Student.builder()
////                .id(1L)
//                .firstName("Test")
//                .surname("Student")
//                .classGrade(ClassGrade.JSS1) // Defaults to N100,000
//                .branch(branch)
//                .parent(parent)
//                .preferredPaymentType(paymentType)
//                .installmentFrequency(frequency)
//                .numberOfInstallments(numInstallments)
//                .build();
//        return student;
//    }
//
//    // Helper to create a mock OnePipeResponse
//    private OnePipeResponse setupMockOnePipeResponse(String requestRef, String transactionRef) {
//        return OnePipeResponse.builder()
//                .status("REQUESTED")
//                .message("Mock invoice created successfully. Virtual account generated.")
//                .paymentId("PAY-MOCK-" + System.currentTimeMillis())
//                .virtualAccountNumber("VIRT-MOCK-" + System.currentTimeMillis())
//                .virtualAccountBankName("Mock Bank PLC")
//                .requestRef(requestRef)
//                .transactionRef(transactionRef)
//                .build();
//    }
//
//    // --- Test for createInitialSchoolFeesPayment ---
//    @Test
//    void testCreateInitialSchoolFeesPayment_Installment_DemoMode() {
//        // 1. SETUP
//        String studentFirstName = "Junior";
//        String studentSurname = "Okonkwo";
//        String parentFirstName = "Nneka";
//        String parentSurname = "Okonkwo";
//
//        Student student = setupMockStudent(PaymentType.INSTALLMENT, InstallmentFrequency.MONTHLY, 3, parentFirstName, parentSurname);
//
//        // Mock repository save calls
//        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
//            Payment p = invocation.getArgument(0);
//            if (p.getId() == null) p.setId(1L); // Simulate ID generation
//            return p;
//        });
//
//        // Mock OnePipe client response
//        OnePipeResponse mockResponse = setupMockOnePipeResponse("req123", "txn123");
//        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(mockResponse);
//
//        // 2. EXECUTE
//        Payment result = paymentService.createInitialSchoolFeesPayment(student);
//
//        // 3. VERIFY
//        // Payment entity updated and saved twice
//        verify(paymentRepository, times(2)).save(any(Payment.class));
//        verify(onePipeClient, times(1)).sendInvoice(any(OnePipeInvoiceRequest.class));
//
//        // Capture the request sent to OnePipeClient
//        ArgumentCaptor<OnePipeInvoiceRequest> onePipeRequestCaptor = ArgumentCaptor.forClass(OnePipeInvoiceRequest.class);
//        verify(onePipeClient).sendInvoice(onePipeRequestCaptor.capture());
//        OnePipeInvoiceRequest capturedRequest = onePipeRequestCaptor.getValue();
//
//        // Verify capturedRequest content for Demo Mode Hacks
//        assertEquals(0, new BigDecimal("10000").compareTo(capturedRequest.getTransaction().getAmount())); // N100.00 in Kobo
//        assertEquals(0, new BigDecimal("2000").compareTo(capturedRequest.getTransaction().getMeta().getDownPayment())); // N20.00 in Kobo
//        assertEquals("daily", capturedRequest.getTransaction().getMeta().getRepeatFrequency()); // Forced daily
//
//        // Verify other meta fields
//        assertEquals("instalment", capturedRequest.getTransaction().getMeta().getType());
//        assertEquals("000019", capturedRequest.getTransaction().getMeta().getBillerCode());
//        assertEquals(3, capturedRequest.getTransaction().getMeta().getNumberOfPayments()); // Number of payments still from student
//
//        // Verify customer details
//        assertEquals(parentFirstName, capturedRequest.getTransaction().getCustomer().getFirstname());
//        assertEquals(parentSurname, capturedRequest.getTransaction().getCustomer().getSurname());
//        assertEquals("parent@test.com", capturedRequest.getTransaction().getCustomer().getEmail());
//        assertEquals("2348012345678", capturedRequest.getTransaction().getCustomer().getMobileNo());
//
//        // Verify Auth secure field (encrypted for installment)
//        assertNotNull(capturedRequest.getAuth().getSecure());
//        assertEquals("bank.account", capturedRequest.getAuth().getType());
//
//        // Verify Payment entity updates
//        assertEquals(PaymentStatus.PENDING, result.getStatus());
//        assertEquals(mockResponse.getVirtualAccountNumber(), result.getVirtualAccountNumber());
//        assertEquals(mockResponse.getPaymentId(), result.getOnePipePaymentId());
//    }
//
//    @Test
//    void testCreateInitialSchoolFeesPayment_Single_DemoMode() {
//        // 1. SETUP
//        Student student = setupMockStudent(PaymentType.SINGLE_PAYMENT, null, null, "Nneka", "Okonkwo");
//
//        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
//            Payment p = invocation.getArgument(0);
//            if (p.getId() == null) p.setId(1L);
//            return p;
//        });
//
//        OnePipeResponse mockResponse = setupMockOnePipeResponse("req456", "txn456");
//        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(mockResponse);
//
//        // 2. EXECUTE
//        Payment result = paymentService.createInitialSchoolFeesPayment(student);
//
//        // 3. VERIFY
//        ArgumentCaptor<OnePipeInvoiceRequest> onePipeRequestCaptor = ArgumentCaptor.forClass(OnePipeInvoiceRequest.class);
//        verify(onePipeClient).sendInvoice(onePipeRequestCaptor.capture());
//        OnePipeInvoiceRequest capturedRequest = onePipeRequestCaptor.getValue();
//
//        // Verify Demo Mode Hacks for Amount
//        assertEquals(0, new BigDecimal("10000").compareTo(capturedRequest.getTransaction().getAmount())); // N100.00 in Kobo
//        assertEquals("single_payment", capturedRequest.getTransaction().getMeta().getType());
//        assertNull(capturedRequest.getAuth().getSecure()); // Not encrypted for single
//        assertNull(capturedRequest.getTransaction().getMeta().getRepeatFrequency());
//        assertNull(capturedRequest.getTransaction().getMeta().getNumberOfPayments());
//        assertEquals(PaymentStatus.PENDING, result.getStatus());
//        assertEquals(mockResponse.getVirtualAccountNumber(), result.getVirtualAccountNumber());
//    }
//
//    // --- Test for triggerNewPayment (Admin initiating payment) ---
//    @Test
//    void testTriggerNewPayment_AdHoc_SinglePayment() {
//        // 1. SETUP
//        Long studentId = 1L;
//        BigDecimal realAmount = new BigDecimal("5000.00"); // Real amount in Naira
//        String description = "Textbook Fee";
//
//        Student student = setupMockStudent(PaymentType.SINGLE_PAYMENT, null, null, "Nneka", "Okonkwo");
//        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
//
//        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
//            Payment p = invocation.getArgument(0);
//            if (p.getId() == null) p.setId(1L);
//            return p;
//        });
//
//        OnePipeResponse mockResponse = setupMockOnePipeResponse("req789", "txn789");
//        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(mockResponse);
//
//        // 2. EXECUTE
//        Payment result = paymentService.triggerNewPayment(studentId, PaymentCategory.ADHOC_PAYMENT, realAmount, PaymentType.SINGLE_PAYMENT, description);
//
//        // 3. VERIFY
//        verify(studentRepository, times(1)).findById(studentId);
//        verify(paymentRepository, times(2)).save(any(Payment.class)); // Initial save + update
//        verify(onePipeClient, times(1)).sendInvoice(any(OnePipeInvoiceRequest.class));
//
//        ArgumentCaptor<OnePipeInvoiceRequest> onePipeRequestCaptor = ArgumentCaptor.forClass(OnePipeInvoiceRequest.class);
//        verify(onePipeClient).sendInvoice(onePipeRequestCaptor.capture());
//        OnePipeInvoiceRequest capturedRequest = onePipeRequestCaptor.getValue();
//
//        // Verify request payload
//        assertEquals(0, new BigDecimal("10000").compareTo(capturedRequest.getTransaction().getAmount())); // N100.00 in Kobo (Demo Mode)
//        assertEquals("single_payment", capturedRequest.getTransaction().getMeta().getType());
//        assertEquals("Textbook Fee", capturedRequest.getTransaction().getTransactionDesc());
//        assertEquals("000019", capturedRequest.getTransaction().getMeta().getBillerCode());
//
//        assertEquals(PaymentStatus.PENDING, result.getStatus());
//        assertEquals(PaymentCategory.ADHOC_PAYMENT, result.getCategory());
//        assertEquals(mockResponse.getVirtualAccountNumber(), result.getVirtualAccountNumber());
//    }
//
//    @Test
//    void testTriggerNewPayment_SchoolFees_Installment_DemoMode() {
//        // 1. SETUP
//        Long studentId = 1L;
//        BigDecimal realAmount = new BigDecimal("100000.00"); // Real amount for school fees
//        String description = "Term 1 School Fees";
//
//        Student student = setupMockStudent(PaymentType.INSTALLMENT, InstallmentFrequency.MONTHLY, 3, "Nneka", "Okonkwo");
//        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
//
//        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
//            Payment p = invocation.getArgument(0);
//            if (p.getId() == null) p.setId(1L);
//            return p;
//        });
//
//        OnePipeResponse mockResponse = setupMockOnePipeResponse("req987", "txn987");
//        when(onePipeClient.sendInvoice(any(OnePipeInvoiceRequest.class))).thenReturn(mockResponse);
//
//        // 2. EXECUTE
//        Payment result = paymentService.triggerNewPayment(studentId, PaymentCategory.SCHOOL_FEES, realAmount, PaymentType.INSTALLMENT, description);
//
//        // 3. VERIFY
//        ArgumentCaptor<OnePipeInvoiceRequest> onePipeRequestCaptor = ArgumentCaptor.forClass(OnePipeInvoiceRequest.class);
//        verify(onePipeClient).sendInvoice(onePipeRequestCaptor.capture());
//        OnePipeInvoiceRequest capturedRequest = onePipeRequestCaptor.getValue();
//
//        // Verify Demo Mode Hacks for Amount and Frequency
//        assertEquals(0, new BigDecimal("10000").compareTo(capturedRequest.getTransaction().getAmount())); // N100.00 in Kobo
//        assertEquals(0, new BigDecimal("2000").compareTo(capturedRequest.getTransaction().getMeta().getDownPayment())); // N20.00 in Kobo
//        assertEquals("daily", capturedRequest.getTransaction().getMeta().getRepeatFrequency()); // Forced daily
//
//        // Verify other fields
//        assertEquals("instalment", capturedRequest.getTransaction().getMeta().getType());
//        assertEquals("000019", capturedRequest.getTransaction().getMeta().getBillerCode());
//        assertEquals(3, capturedRequest.getTransaction().getMeta().getNumberOfPayments()); // Number of payments still from student
//        assertNotNull(capturedRequest.getAuth().getSecure()); // Should be encrypted
//        assertEquals(PaymentStatus.PENDING, result.getStatus());
//        assertEquals(PaymentCategory.SCHOOL_FEES, result.getCategory());
//        assertEquals(mockResponse.getVirtualAccountNumber(), result.getVirtualAccountNumber());
//    }

//    @Test
//    void testCalculateDates() {
//        Payment payment = Payment.builder()
//                .installmentFrequency(InstallmentFrequency.DAILY)
//                .numberOfPayments(1)
//                .build();
//
//        // Mock current time to ensure consistent tests
//        // In real tests, you might want to freeze time using a library like Awaitility or similar.
//        // For now, we'll just check format and a rough future date.
//
//        String startDate = paymentService.calculateStartDate(payment);
//        String endDate = paymentService.calculateEndDate(payment);
//
//        assertNotNull(startDate);
//        assertNotNull(endDate);
//        assertTrue(startDate.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}"));
//        assertTrue(endDate.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}"));
//    }
//}