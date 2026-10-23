package com.onepipe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.config.JwtService;
import com.onepipe.data.entities.*;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.dtos.request.*;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.location=classpath:/")
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(initializers = PaymentFinancialIntegrationTest.IsolatedEnvironment.class)
class PaymentFinancialIntegrationTest {
    private static final byte[] JWT_KEY = randomKey();
    private static final DateTimeFormatter PROVIDER_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    @DynamicPropertySource
    static void privateKey(DynamicPropertyRegistry properties) {
        properties.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(JWT_KEY));
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired ParentRepository parents;
    @Autowired BranchRepository branches;
    @Autowired StudentRepository students;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @SpyBean MockOnepipeClient provider;
    private User ownerUser, otherUser, adminUser, superUser;
    private Parent owner;
    private Branch branch;
    private Student child, otherChild;
    private OnePipeResponse existingMock;

    @BeforeEach
    void fixtures() {
        clearDatabase();
        ownerUser = user("owner", Role.PARENT); otherUser = user("other", Role.PARENT);
        adminUser = user("admin", Role.BRANCH_ADMIN); superUser = user("super", Role.SUPER_ADMIN);
        branch = branches.save(Branch.builder().adminUser(adminUser).businessName("Synthetic campus")
                .businessShortName("DEMO").billerCode("MOCK-FINANCIAL-TEST").build());
        owner = parent(ownerUser, "OWNER");
        child = child(owner, "Child"); otherChild = child(parent(otherUser, "OTHER"), "Other");
        var invoice = new OnePipeInvoiceRequest(); invoice.setRequestRef(UUID.randomUUID().toString());
        var tx = new OnePipeInvoiceRequest.Transaction(); tx.setTransactionRef(UUID.randomUUID().toString());
        tx.setAmount(new BigDecimal("12345")); invoice.setTransaction(tx);
        existingMock = provider.sendInvoice(invoice);
        clearInvocations(provider);
    }

    @AfterEach void cleanup() { clearDatabase(); }

    @Test
    void singleInvoiceUsesFullAdhocAmountAndKeepsExistingResponseFields() throws Exception {
        var result = createInvoice(invoice("ADHOC_PAYMENT", "275.25", "SINGLE_PAYMENT"));
        var details = json.readTree(result.getResponse().getContentAsString()).get("paymentDetails");
        assertThat(details.get("amount").decimalValue()).isEqualByComparingTo("275.25");
        assertThat(details.has("bankName")).isTrue(); assertThat(details.has("accountName")).isTrue();
        assertThat(details.has("accountNumber")).isTrue(); assertThat(details.has("customerAccountNumber")).isTrue();
        assertThat(details.has("expiryDate")).isTrue(); assertThat(details.has("qrCodeImage")).isTrue();
        Payment payment = payments.findAll().get(0);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getDownPaymentAmount()).isNull(); assertThat(payment.getNumberOfPayments()).isNull();
        assertThat(payment.getInstallmentFrequency()).isNull();
        var request = providerRequest();
        assertThat(request.getTransaction().getAmount()).isEqualTo(new BigDecimal("27525"));
        assertThat(request.getTransaction().getMeta().getType()).isEqualTo("single_payment");
        assertThat(request.getTransaction().getMeta().getRepeatFrequency()).isNull();
        assertThat(request.getTransaction().getMeta().getDownPayment()).isNull();
        http.perform(as(ownerUser, get("/api/payments").param("studentId", child.getId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].remainingAmount").value(275.25));
    }

    @ParameterizedTest
    @EnumSource(InstallmentFrequency.class)
    void hundredThousandNairaWithThreeInstallmentsFlowsThroughDatabaseApiAndMock(InstallmentFrequency frequency) throws Exception {
        var input = invoice("ADHOC_PAYMENT", "100000.00", "INSTALLMENT");
        input.put("frequency", frequency.name()); input.put("numberOfInstallments", 3);
        var result = createInvoice(input);
        var details = json.readTree(result.getResponse().getContentAsString()).get("paymentDetails");
        assertThat(details.get("amount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(details.get("downPayment").decimalValue()).isEqualByComparingTo("20000.00");
        var terms = details.get("terms");
        assertThat(terms.get("currency").asText()).isEqualTo("NGN");
        assertThat(terms.get("balanceBasis").asText()).isEqualTo("UNPAID");
        assertThat(terms.get("remainingAmount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(terms.get("installmentAmounts").get(0).decimalValue()).isEqualByComparingTo("26666.67");
        assertThat(terms.get("installmentAmounts").get(1).decimalValue()).isEqualByComparingTo("26666.67");
        assertThat(terms.get("installmentAmounts").get(2).decimalValue()).isEqualByComparingTo("26666.66");
        Payment payment = payments.findAll().get(0);
        assertThat(payment.getTotalAmount()).isEqualTo(new BigDecimal("100000.00"));
        assertThat(payment.getDownPaymentAmount()).isEqualTo(new BigDecimal("20000.00"));
        assertThat(payment.getAmountPerCycle()).isEqualTo(new BigDecimal("26666.67"));
        assertThat(payment.getNumberOfPayments()).isEqualTo(3); assertThat(payment.getCompletedPayments()).isZero();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        var request = providerRequest();
        var meta = request.getTransaction().getMeta();
        assertThat(request.getTransaction().getAmount()).isEqualTo(new BigDecimal("10000000"));
        assertThat(meta.getDownPayment()).isEqualTo(new BigDecimal("2000000"));
        assertThat(meta.getNumberOfPayments()).isEqualTo(3);
        assertThat(meta.getRepeatFrequency()).isEqualTo(frequency.name().toLowerCase(Locale.ROOT));
        assertThat(meta.getRepeatEndDate()).isNull();
        assertThat(meta.getRepeatStartDate()).isEqualTo(new PaymentCalculator()
                .nextBillingDate(payment.getBillingAnchor(), frequency).format(PROVIDER_DATE));
        OnePipeResponse mock = provider.queryTransaction(payment.getTransactionRef());
        assertThat(mock.getAmountInKobo()).isEqualTo(new BigDecimal("10000000"));
        assertThat(mock.getDownPaymentInKobo()).isEqualTo(new BigDecimal("2000000"));
        assertThat(mock.getRepeatFrequency()).isEqualTo(meta.getRepeatFrequency());
        assertThat(mock.getRepeatStartDate()).isEqualTo(meta.getRepeatStartDate());
        assertThat(mock.getNumberOfPayments()).isEqualTo(3); assertThat(mock.getStatus()).isEqualTo("Pending");
        http.perform(as(ownerUser, get("/api/payments").param("studentId", child.getId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].remainingAmount").value(100000))
                .andExpect(jsonPath("$[0].numberOfPayments").value(3))
                .andExpect(jsonPath("$[0].completedPayments").value(0));
        http.perform(as(adminUser, get("/api/payments").param("branchId", branch.getId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].amount").value(100000))
                .andExpect(jsonPath("$[0].terms.installmentAmounts.length()").value(3));
    }

    @ParameterizedTest
    @EnumSource(InstallmentFrequency.class)
    void subscriptionUsesSelectedFrequencyFirstDateAndUnlimitedPerCycleAmount(InstallmentFrequency frequency) throws Exception {
        var input = invoice("ADHOC_PAYMENT", "1234.56", "SUBSCRIPTION"); input.put("frequency", frequency.name());
        var result = createInvoice(input);
        Payment payment = payments.findAll().get(0);
        assertThat(payment.getAmountPerCycle()).isEqualTo(new BigDecimal("1234.56"));
        assertThat(payment.getDownPaymentAmount()).isNull(); assertThat(payment.getNumberOfPayments()).isNull();
        assertThat(payment.getInstallmentFrequency()).isEqualTo(frequency);
        var request = providerRequest(); var meta = request.getTransaction().getMeta();
        assertThat(meta.getType()).isEqualTo("subscription");
        assertThat(meta.getRepeatFrequency()).isEqualTo(frequency.name().toLowerCase(Locale.ROOT));
        assertThat(meta.getRepeatStartDate()).isEqualTo(new PaymentCalculator()
                .nextBillingDate(payment.getBillingAnchor(), frequency).format(PROVIDER_DATE));
        assertThat(meta.getRepeatEndDate()).isNull(); assertThat(meta.getDownPayment()).isNull();
        assertThat(meta.getNumberOfPayments()).isNull();
        var mock = provider.queryTransaction(payment.getTransactionRef());
        assertThat(mock.getAmountInKobo()).isEqualTo(new BigDecimal("123456"));
        assertThat(mock.getRepeatEndDate()).isNull(); assertThat(mock.getStatus()).isEqualTo("Pending");
        var terms = json.readTree(result.getResponse().getContentAsString()).get("paymentDetails").get("terms");
        assertThat(terms.get("balanceBasis").asText()).isEqualTo("UNBOUNDED_SUBSCRIPTION");
        assertThat(terms.get("remainingAmount").isNull()).isTrue(); assertThat(terms.get("endDate").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void schoolFeeInvoicesUseConfiguredFeeWithOptionalMatchingCallerAmount(boolean submitAmount) throws Exception {
        var input = invoice("SCHOOL_FEES", "100000.00", "SINGLE_PAYMENT"); if (!submitAmount) input.remove("amount");
        createInvoice(input);
        assertThat(payments.findAll().get(0).getTotalAmount()).isEqualTo(new BigDecimal("100000.00"));
        assertThat(providerRequest().getTransaction().getAmount()).isEqualTo(new BigDecimal("10000000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"500.00", "99999.99", "100000.01", "200000.00"})
    void schoolFeeTamperingIsRejectedWithoutSideEffects(String amount) throws Exception {
        rejectWithoutEffects(as(ownerUser, post("/api/payments/new").contentType("application/json")
                .content(json.writeValueAsString(invoice("SCHOOL_FEES", amount, "SINGLE_PAYMENT")))), 400);
    }

    @Test
    void subsequentFeesUseCurrentServerFeeAndDoNotRewritePreviouslySettledInvoice() throws Exception {
        payments.save(Payment.builder().student(child).parent(owner).branch(branch).category(PaymentCategory.SCHOOL_FEES)
                .paymentType(PaymentType.SINGLE_PAYMENT).status(PaymentStatus.SUCCESSFUL)
                .totalAmount(new BigDecimal("500.00")).completedPayments(0).build());
        createInvoice(invoice("SCHOOL_FEES", "100000.00", "SINGLE_PAYMENT"));
        assertThat(payments.findAll()).extracting(Payment::getTotalAmount)
                .containsExactlyInAnyOrder(new BigDecimal("500.00"), new BigDecimal("100000.00"));
    }

    @Test
    void initialRegistrationUsesSameServerFeeAndAcceptsLegacyCountAlias() throws Exception {
        var request = registration(); request.setPaymentType(PaymentType.INSTALLMENT);
        request.setFrequency(InstallmentFrequency.MONTHLY); request.setNumberOfInstallments(3);
        var body = json.valueToTree(request); ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("numberOfInstallments");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("numberOfPayments", 3);
        http.perform(post("/api/students/register").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentDetails.amount").value(100000))
                .andExpect(jsonPath("$.paymentDetails.downPayment").value(20000))
                .andExpect(jsonPath("$.paymentDetails.terms.remainingAmount").value(100000))
                .andExpect(jsonPath("$.paymentDetails.terms.installmentAmounts.length()").value(3));
        assertThat(payments.findAll().get(0).getNumberOfPayments()).isEqualTo(3);
        assertThat(providerRequest().getTransaction().getAmount()).isEqualTo(new BigDecimal("10000000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tamperedFee", "missingType", "missingClass", "missingChildName", "missingDate",
            "missingParentEmail", "missingParentPhone", "missingNewParentPassword", "negativeAmount", "precision",
            "installmentMissingCount", "installmentMissingFrequency", "subscriptionMissingFrequency"})
    void invalidRegistrationNeverCreatesParentsStudentsPaymentsOrProviderState(String problem) throws Exception {
        var input = registration();
        switch (problem) {
            case "tamperedFee" -> input.setAmount(new BigDecimal("500.00"));
            case "missingType" -> input.setPaymentType(null);
            case "missingClass" -> input.setClassGrade(null);
            case "missingChildName" -> input.setFirstName(" ");
            case "missingDate" -> input.setDateOfBirth(null);
            case "missingParentEmail" -> input.setParentEmail(null);
            case "missingParentPhone" -> input.setParentPhoneNumber(null);
            case "missingNewParentPassword" -> input.setParentPassword(null);
            case "negativeAmount" -> input.setAmount(new BigDecimal("-1"));
            case "precision" -> input.setAmount(new BigDecimal("100000.001"));
            case "installmentMissingCount" -> {input.setPaymentType(PaymentType.INSTALLMENT); input.setFrequency(InstallmentFrequency.WEEKLY);}
            case "installmentMissingFrequency" -> {input.setPaymentType(PaymentType.INSTALLMENT); input.setNumberOfInstallments(3);}
            case "subscriptionMissingFrequency" -> input.setPaymentType(PaymentType.SUBSCRIPTION);
        }
        rejectWithoutEffects(post("/api/students/register").contentType("application/json")
                .content(json.writeValueAsString(input)), 400);
    }

    @ParameterizedTest
    @MethodSource("invalidInvoices")
    void malformedOrIncompleteInvoicesAre400AndHaveNoSideEffects(String body) throws Exception {
        rejectWithoutEffects(as(ownerUser, post("/api/payments/new").contentType("application/json")
                .content(body.replace("STUDENT_ID", child.getId().toString()))), 400);
    }

    static Stream<String> invalidInvoices() {
        String valid = "{\"studentId\":STUDENT_ID,\"category\":\"ADHOC_PAYMENT\",\"paymentType\":\"SINGLE_PAYMENT\",\"amount\":AMOUNT}";
        Stream<String> amounts = Stream.of("0", "-1", "1.001", "100.000", "null", "\"nonsense\"", "true", "{}")
                .map(value -> valid.replace("AMOUNT", value));
        String base = valid.replace("AMOUNT", "100000.00");
        Stream<String> fields = Stream.of(base.replace("\"ADHOC_PAYMENT\"", "null"),
                base.replace("\"ADHOC_PAYMENT\"", "\"OTHER\""), base.replace("\"ADHOC_PAYMENT\"", "1"),
                base.replace("\"SINGLE_PAYMENT\"", "null"), base.replace("\"SINGLE_PAYMENT\"", "\"OTHER\""),
                base.replace("\"SINGLE_PAYMENT\"", "1"), base.replace("STUDENT_ID", "null"),
                base.replace("STUDENT_ID", "1.5"), "{}", "null", "{");
        String installment = base.replace("SINGLE_PAYMENT", "INSTALLMENT");
        Stream<String> plans = Stream.of(installment, installment.replace("}", ",\"frequency\":\"WEEKLY\"}"),
                installment.replace("}", ",\"numberOfInstallments\":3}"),
                installment.replace("}", ",\"frequency\":\"WEEKLY\",\"numberOfInstallments\":0}"),
                installment.replace("}", ",\"frequency\":\"WEEKLY\",\"numberOfInstallments\":-1}"),
                installment.replace("}", ",\"frequency\":\"WEEKLY\",\"numberOfInstallments\":10001}"),
                installment.replace("}", ",\"frequency\":\"WEEKLY\",\"numberOfInstallments\":3.5}"),
                installment.replace("}", ",\"frequency\":\"FORTNIGHTLY\",\"numberOfInstallments\":3}"),
                base.replace("SINGLE_PAYMENT", "SUBSCRIPTION"));
        return Stream.of(amounts, fields, plans).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingType", "missingCount", "zeroCount", "negativeCount", "missingFrequency", "subscriptionMissingFrequency", "partialBanking", "blankBanking"})
    void invalidPlanChangesCannotAlterStoredPlansOrBanking(String problem) throws Exception {
        var request = new SwitchPlanRequest(); request.setNewPaymentType(PaymentType.INSTALLMENT);
        request.setFrequency(InstallmentFrequency.MONTHLY); request.setNumberOfInstallments(3);
        switch (problem) {
            case "missingType" -> request.setNewPaymentType(null);
            case "missingCount" -> request.setNumberOfInstallments(null);
            case "zeroCount" -> request.setNumberOfInstallments(0);
            case "negativeCount" -> request.setNumberOfInstallments(-1);
            case "missingFrequency" -> request.setFrequency(null);
            case "subscriptionMissingFrequency" -> { request.setNewPaymentType(PaymentType.SUBSCRIPTION); request.setFrequency(null); }
            case "partialBanking" -> request.setBankAccountNumber("DEMO-REPLACEMENT");
            case "blankBanking" -> {request.setBankAccountNumber(" "); request.setBankCode(" ");}
        }
        rejectWithoutEffects(as(ownerUser, put("/api/payments/students/{id}/switch-plan", child.getId())
                .contentType("application/json").content(json.writeValueAsString(request))), 400);
    }

    @Test
    void parentPlanFrequencySurvivesSwitchAndIsUsedByNextSchoolFeeInvoice() throws Exception {
        var request = new SwitchPlanRequest(); request.setNewPaymentType(PaymentType.SUBSCRIPTION);
        request.setFrequency(InstallmentFrequency.WEEKLY);
        http.perform(as(ownerUser, put("/api/payments/students/{id}/switch-plan", child.getId())
                .contentType("application/json").content(json.writeValueAsString(request)))).andExpect(status().isOk());
        assertThat(students.findById(child.getId()).orElseThrow().getInstallmentFrequency()).isEqualTo(InstallmentFrequency.WEEKLY);
        createInvoice(invoice("SCHOOL_FEES", "100000.00", "SUBSCRIPTION"));
        assertThat(providerRequest().getTransaction().getMeta().getRepeatFrequency()).isEqualTo("weekly");
    }

    @Test
    void schoolFeeCreationCannotBypassParentOnlyPlanSwitching() throws Exception {
        var input = invoice("SCHOOL_FEES", "100000.00", "INSTALLMENT");
        input.put("frequency", "WEEKLY"); input.put("numberOfInstallments", 3);
        rejectWithoutEffects(as(ownerUser, post("/api/payments/new").contentType("application/json")
                .content(json.writeValueAsString(input))), 400);
    }

    @Test
    void validOngoingInvoiceConflictRemains409() throws Exception {
        createInvoice(invoice("SCHOOL_FEES", "100000.00", "SINGLE_PAYMENT")); clearInvocations(provider);
        rejectWithoutEffects(as(ownerUser, post("/api/payments/new").contentType("application/json")
                .content(json.writeValueAsString(invoice("SCHOOL_FEES", "100000.00", "SINGLE_PAYMENT")))), 409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreignParent", "branchAdmin", "superAdmin"})
    void planAuthorizationStillRunsBeforeFinancialValidation(String caller) throws Exception {
        User user = switch (caller) { case "foreignParent" -> otherUser; case "branchAdmin" -> adminUser; default -> superUser; };
        rejectWithoutEffects(as(user, put("/api/payments/students/{id}/switch-plan", child.getId())
                .contentType("application/json").content("{}")), 403);
    }

    @Test
    void foreignStudentInvoiceIsDeniedBeforeAmountValidation() throws Exception {
        var input = invoice("ADHOC_PAYMENT", "-1", "SINGLE_PAYMENT"); input.put("studentId", otherChild.getId());
        rejectWithoutEffects(as(ownerUser, post("/api/payments/new").contentType("application/json")
                .content(json.writeValueAsString(input))), 403);
    }

    private MvcResult createInvoice(Map<String, Object> input) throws Exception {
        return http.perform(as(ownerUser, post("/api/payments/new").contentType("application/json")
                .content(json.writeValueAsString(input)))).andExpect(status().isOk()).andReturn();
    }

    private Map<String, Object> invoice(String category, String amount, String type) {
        Map<String, Object> input = new LinkedHashMap<>(); input.put("studentId", child.getId());
        input.put("category", category); input.put("paymentType", type); input.put("amount", new BigDecimal(amount)); return input;
    }

    private OnePipeInvoiceRequest providerRequest() {
        var capture = ArgumentCaptor.forClass(OnePipeInvoiceRequest.class);
        verify(provider).sendInvoice(capture.capture(), any(Payment.class)); return capture.getValue();
    }

    private void rejectWithoutEffects(MockHttpServletRequestBuilder request, int statusCode) throws Exception {
        List<?> before = snapshot(); clearInvocations(provider);
        http.perform(request).andExpect(status().is(statusCode)).andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.code").isString()).andExpect(jsonPath("$.path").isString());
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
        assertThat(provider.queryTransaction(existingMock.getTransactionRef())).isEqualTo(existingMock);
    }

    private List<?> snapshot() {
        return Stream.of("app_users", "parents", "branches", "students", "payments")
                .map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        var details = org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword()).roles(user.getRole().name()).build();
        return request.header("Authorization", "Bearer " + jwt.generateToken(details));
    }

    private RegisterStudentRequest registration() {
        return RegisterStudentRequest.builder().branchId(branch.getId()).firstName("New child").surname("Demo")
                .dateOfBirth(LocalDate.of(2012, 1, 1)).classGrade(ClassGrade.JSS1).parentTitle(Title.MRS)
                .parentFirstName("New").parentSurname("Parent").parentEmail("new-" + UUID.randomUUID() + "@example.invalid")
                .parentPassword(UUID.randomUUID().toString()).parentPhoneNumber("DEMO-" + UUID.randomUUID())
                .paymentType(PaymentType.SINGLE_PAYMENT).bankAccountNumber("DEMO-ACCOUNT").bankCode("DEMO-BANK").build();
    }

    private User user(String label, Role role) {
        return users.save(User.builder().email(label + "@example.invalid").password(UUID.randomUUID().toString()).role(role).build());
    }
    private Parent parent(User user, String label) {
        return parents.save(Parent.builder().user(user).firstName(label).surname("Demo").phoneNumber("DEMO-" + label)
                .bankAccountNumber("DEMO-ACCOUNT-" + label).bankCode("DEMO-BANK").build());
    }
    private Student child(Parent parent, String label) {
        return students.save(Student.builder().parent(parent).branch(branch).firstName(label).surname("Demo")
                .studentRegId(UUID.randomUUID().toString()).classGrade(ClassGrade.JSS1)
                .preferredPaymentType(PaymentType.SINGLE_PAYMENT).build());
    }
    private void clearDatabase() {
        payments.deleteAll(); students.deleteAll(); parents.deleteAll(); branches.deleteAll(); users.deleteAll();
    }
    private static byte[] randomKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
