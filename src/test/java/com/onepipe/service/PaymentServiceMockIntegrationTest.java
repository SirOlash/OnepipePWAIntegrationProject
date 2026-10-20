package com.onepipe.service;

import com.onepipe.config.OnepipeConfiguration;
import com.onepipe.controllers.TestController;
import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.InstallmentFrequency;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentStatus;
import com.onepipe.data.enums.PaymentType;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.PaymentRepository;
import com.onepipe.data.repositories.StudentRepository;
import com.onepipe.dtos.response.RegisterStudentResponse;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.RealOnePipeClient;
import com.onepipe.integration.dto.OnePipeQueryResponse;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.utils.EncryptionUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceMockIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    context.getEnvironment().getPropertySources()
                            .addLast(new ResourcePropertySource("classpath:application.properties"));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            })
            .withUserConfiguration(OnepipeConfiguration.class, MockOnepipeClient.class,
                    RealOnePipeClient.class, TestController.class, PaymentService.class)
            .withBean(PaymentRepository.class, () -> mock(PaymentRepository.class))
            .withBean(StudentRepository.class, () -> mock(StudentRepository.class))
            .withBean(ParentRepository.class, () -> mock(ParentRepository.class))
            .withBean(BranchRepository.class, () -> mock(BranchRepository.class));

    @ParameterizedTest
    @EnumSource(PaymentType.class)
    void createsPendingInvoicesWithoutProviderCredentialsOrBankDetails(PaymentType paymentType) {
        try (MockedConstruction<RestTemplate> httpClients = mockConstruction(RestTemplate.class);
             MockedStatic<EncryptionUtil> encryption = mockStatic(EncryptionUtil.class)) {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(OnepipeClient.class)
                        .hasSingleBean(MockOnepipeClient.class).doesNotHaveBean(RealOnePipeClient.class)
                        .doesNotHaveBean(TestController.class);
                assertThat(context.getEnvironment().getProperty("ONEPIPE_API_KEY")).isNull();
                assertThat(context.getEnvironment().getProperty("ONEPIPE_CLIENT_SECRET")).isNull();
                assertThat(context.getEnvironment().getProperty("ONEPIPE_BASE_URL")).isNull();
                assertThat(context.getEnvironment().getProperty("ONEPIPE_WEBHOOK_URL")).isNull();

                Student student = demoStudent(paymentType);
                StudentRepository students = context.getBean(StudentRepository.class);
                PaymentRepository payments = context.getBean(PaymentRepository.class);
                when(students.findById(student.getId())).thenReturn(Optional.of(student));
                when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    payment.setId(1L);
                    return payment;
                });

                PaymentService service = context.getBean(PaymentService.class);
                RegisterStudentResponse response = service.triggerNewPayment(student.getId(),
                        PaymentCategory.ADHOC_PAYMENT, new BigDecimal("500.00"), paymentType,
                        "Demo invoice with no payment processing");

                ArgumentCaptor<Payment> savedPayments = ArgumentCaptor.forClass(Payment.class);
                verify(payments, times(2)).save(savedPayments.capture());
                Payment payment = savedPayments.getValue();
                assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
                assertThat(payment.getCompletedPayments()).isZero();
                assertThat(payment.getOnePipePaymentId()).startsWith("MOCK-PAY-");
                assertThat(payment.getVirtualAccountNumber()).startsWith("DEMO-ACCOUNT-");
                assertThat(payment.getVirtualAccountName()).containsIgnoringCase("demo");
                assertThat(payment.getVirtualAccountBankName()).containsIgnoringCase("demo");

                OnePipeResponse providerInvoice = context.getBean(OnepipeClient.class)
                        .queryTransaction(payment.getTransactionRef());
                assertThat(providerInvoice.getStatus()).isEqualTo("Pending");
                assertThat(payment.getOnePipePaymentId()).isEqualTo(providerInvoice.getPaymentId());
                DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                assertThat(LocalDateTime.parse(payment.getVirtualAccountExpiryDate(), dateFormat))
                        .isAfter(LocalDateTime.parse(providerInvoice.getCreatedOn(), dateFormat));

                RegisterStudentResponse.PaymentDetails details = response.getPaymentDetails();
                assertThat(details.getAmount()).isEqualByComparingTo("500.00");
                assertThat(details.getPaymentType()).isEqualTo(paymentType.name());
                assertThat(details.getAccountNumber()).isEqualTo(payment.getVirtualAccountNumber());
                assertThat(details.getAccountName()).isEqualTo(payment.getVirtualAccountName());
                assertThat(details.getBankName()).isEqualTo(payment.getVirtualAccountBankName());
                assertThat(details.getExpiryDate()).isEqualTo(payment.getVirtualAccountExpiryDate());
                assertThat(details.getCustomerAccountNumber()).isEqualTo(payment.getCustomerAccountNumber());

                when(payments.findByOnePipePaymentId(payment.getOnePipePaymentId()))
                        .thenReturn(Optional.of(payment));
                OnePipeQueryResponse query = service.queryAndFixPaymentStatus(payment.getOnePipePaymentId());
                assertThat(query.getStatus()).isEqualTo("PENDING");
                assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
                assertThat(payment.getCompletedPayments()).isZero();
            });
            assertThat(httpClients.constructed()).isEmpty();
            encryption.verifyNoInteractions();
        }
    }

    private Student demoStudent(PaymentType paymentType) {
        Parent parent = Parent.builder()
                .user(User.builder().email("demo-parent@example.invalid").build())
                .firstName("Demo")
                .surname("Parent")
                .phoneNumber("DEMO-PARENT")
                .build();
        Student student = Student.builder()
                .firstName("Demo")
                .surname("Student")
                .studentRegId("DEMO-STUDENT-1")
                .parent(parent)
                .branch(Branch.builder().billerCode("MOCK-MERCHANT-SERVICE-TEST").build())
                .preferredPaymentType(paymentType)
                .installmentFrequency(InstallmentFrequency.MONTHLY)
                .numberOfInstallments(3)
                .build();
        student.setId(1L);
        return student;
    }
}
