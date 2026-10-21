package com.onepipe.service;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.ClassGrade;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.security.ResourceAuthorization;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentServiceTransactionReferenceTest {

    private static final String UUID_V4_SUFFIX =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StudentRepository students = mock(StudentRepository.class);
    private final MockOnepipeClient provider = new MockOnepipeClient();
    private final ResourceAuthorization authorization = mock(ResourceAuthorization.class);
    private final PaymentService service = new PaymentService(payments, provider, students,
            mock(ParentRepository.class), mock(BranchRepository.class), authorization);
    private final Map<Long, Payment> savedByStudent = new HashMap<>();

    @BeforeEach
    void savePaymentsWithoutADatabase() {
        when(authorization.student(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
                students.findById(invocation.getArgument(0)).orElseThrow());
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            savedByStudent.put(payment.getStudent().getId(), payment);
            return payment;
        });
        when(payments.findFirstByStudentAndCategoryAndStatusIn(
                any(Student.class), eq(PaymentCategory.SCHOOL_FEES), anyList()))
                .thenReturn(Optional.empty());
    }

    @Test
    void initialSchoolFeesRequestsCreateSeparateUuidTransactionsAndMockInvoices() {
        Student firstStudent = demoStudent(1L);
        Student secondStudent = demoStudent(2L);

        Payment first = service.createInitialSchoolFeesPayment(firstStudent);
        Payment second = service.createInitialSchoolFeesPayment(secondStudent);

        assertThat(first.getCategory()).isEqualTo(PaymentCategory.SCHOOL_FEES);
        assertThat(second.getCategory()).isEqualTo(PaymentCategory.SCHOOL_FEES);
        assertSeparatePendingInvoices(first, second, "SCH-", ClassGrade.JSS1.getDefaultFee());
    }

    @ParameterizedTest
    @EnumSource(PaymentCategory.class)
    void triggeredPaymentsKeepCategoryPrefixesWithoutSharingMockInvoices(PaymentCategory category) {
        Student firstStudent = demoStudent(1L);
        Student secondStudent = demoStudent(2L);
        when(students.findById(firstStudent.getId())).thenReturn(Optional.of(firstStudent));
        when(students.findById(secondStudent.getId())).thenReturn(Optional.of(secondStudent));
        BigDecimal requestedAmount = new BigDecimal("275.25");

        RegisterStudentResponse firstResponse = service.triggerNewPayment(firstStudent.getId(), category,
                requestedAmount, PaymentType.SINGLE_PAYMENT, "First demo invoice");
        RegisterStudentResponse secondResponse = service.triggerNewPayment(secondStudent.getId(), category,
                requestedAmount, PaymentType.SINGLE_PAYMENT, "Second demo invoice");

        Payment first = savedByStudent.get(firstStudent.getId());
        Payment second = savedByStudent.get(secondStudent.getId());
        assertSeparatePendingInvoices(first, second, category.name() + "-", requestedAmount);
        assertThat(firstResponse.getPaymentDetails().getAccountNumber())
                .isEqualTo(first.getVirtualAccountNumber());
        assertThat(secondResponse.getPaymentDetails().getAccountNumber())
                .isEqualTo(second.getVirtualAccountNumber());
    }

    private void assertSeparatePendingInvoices(Payment first, Payment second, String prefix,
                                              BigDecimal expectedAmount) {
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // The format assertion rejects timestamp-only references even when sequential calls span milliseconds.
        assertThat(first.getTransactionRef()).matches(Pattern.quote(prefix) + UUID_V4_SUFFIX);
        assertThat(second.getTransactionRef()).matches(Pattern.quote(prefix) + UUID_V4_SUFFIX)
                .isNotEqualTo(first.getTransactionRef());
        assertThat(first.getOnePipePaymentId()).startsWith("MOCK-PAY-");
        assertThat(second.getOnePipePaymentId()).startsWith("MOCK-PAY-")
                .isNotEqualTo(first.getOnePipePaymentId());
        assertThat(first.getVirtualAccountNumber()).startsWith("DEMO-ACCOUNT-");
        assertThat(second.getVirtualAccountNumber()).startsWith("DEMO-ACCOUNT-")
                .isNotEqualTo(first.getVirtualAccountNumber());
        for (Payment payment : new Payment[]{first, second}) {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getCompletedPayments()).isZero();
            assertThat(payment.getTotalAmount()).isEqualByComparingTo(expectedAmount);
            OnePipeResponse queried = provider.queryTransaction(payment.getTransactionRef());
            assertThat(queried.getStatus()).isEqualTo("Pending");
            assertThat(queried.getPaymentId()).isEqualTo(payment.getOnePipePaymentId());
            assertThat(queried.getVirtualAccountNumber()).isEqualTo(payment.getVirtualAccountNumber());
        }
    }

    private Student demoStudent(Long id) {
        Parent parent = Parent.builder()
                .user(User.builder().email("demo-parent@example.invalid").build())
                .firstName("Demo")
                .surname("Parent")
                .phoneNumber("DEMO-PARENT")
                .build();
        Student student = Student.builder()
                .firstName("Demo")
                .surname("Student")
                .studentRegId("DEMO-STUDENT-" + id)
                .classGrade(ClassGrade.JSS1)
                .parent(parent)
                .branch(Branch.builder().billerCode("MOCK-MERCHANT-REFERENCE-TEST").build())
                .preferredPaymentType(PaymentType.SINGLE_PAYMENT)
                .build();
        student.setId(id);
        return student;
    }
}
