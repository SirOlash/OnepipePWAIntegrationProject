package com.onepipe.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.config.JwtService;
import com.onepipe.data.entities.*;
import com.onepipe.data.enums.*;
import com.onepipe.data.repositories.*;
import com.onepipe.dtos.request.*;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.integration.dto.OnePipeInvoiceRequest;
import com.onepipe.integration.dto.OnePipeResponse;
import com.onepipe.service.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.location=classpath:/")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@ContextConfiguration(initializers = AuthorizationIntegrationTest.IsolatedEnvironment.class)
class AuthorizationIntegrationTest {
    private static final byte[] JWT_KEY = randomKey();

    @DynamicPropertySource
    static void privateJwt(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(JWT_KEY));
    }

    @Autowired private MockMvc http;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private PasswordEncoder encoder;
    @Autowired private UserRepository users;
    @Autowired private ParentRepository parents;
    @Autowired private BranchRepository branches;
    @Autowired private StudentRepository students;
    @Autowired private PaymentRepository payments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService paymentService;
    @Autowired private StudentService studentService;
    @Autowired private BranchService branchService;
    @Autowired private DashboardService dashboardService;
    @SpyBean private MockOnepipeClient provider;

    private final Map<String, User> actors = new HashMap<>();
    private final Map<String, String> passwords = new HashMap<>();
    private Branch branchA, branchB;
    private Parent parentA, parentB;
    private Student childA, childB, childAcrossBranches;
    private Payment paymentA, paymentB, paymentAcrossBranches;

    @BeforeEach
    void fixtures() {
        clearDatabase();
        actor("super", Role.SUPER_ADMIN);
        branchA = branch(actor("branchA", Role.BRANCH_ADMIN), "A");
        branchB = branch(actor("branchB", Role.BRANCH_ADMIN), "B");
        parentA = parent(actor("parentA", Role.PARENT), "A");
        parentB = parent(actor("parentB", Role.PARENT), "B");
        childA = child(parentA, branchA, "ChildA");
        childB = child(parentB, branchB, "ChildB");
        childAcrossBranches = child(parentA, branchB, "ChildAcrossBranches");
        paymentA = payment(childA);
        paymentB = payment(childB);
        paymentAcrossBranches = payment(childAcrossBranches);
        clearInvocations(provider);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        clearDatabase();
    }

    private void clearDatabase() {
        payments.deleteAll(); students.deleteAll(); parents.deleteAll(); branches.deleteAll(); users.deleteAll();
        actors.clear(); passwords.clear();
    }

    @ParameterizedTest(name = "{0} cannot perform {1} on another tenant")
    @MethodSource("foreignOperations")
    void crossTenantOperationsFailBeforeAnyProviderCallOrDatabaseChange(String caller, String operation) throws Exception {
        boolean targetB = caller.endsWith("A");
        rejected(caller, operation(operation, targetB), 403);
        assertThat(provider.queryTransaction((targetB ? paymentB : paymentA).getTransactionRef()).getStatus()).isEqualTo("Pending");
    }

    static Stream<Arguments> foreignOperations() {
        return Stream.of("parentA", "branchA", "parentB", "branchB").flatMap(caller ->
                Stream.of("student-payments", "invoice", "query", "cancel", "plan")
                        .map(operation -> Arguments.of(caller, operation)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"branch-students", "branch-payments"})
    void aParentCannotUseAnyBranchWideListing(String operation) throws Exception {
        rejected("parentA", operation(operation, false), 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"branch-students", "branch-payments"})
    void aBranchAdminCannotListAnotherBranch(String operation) throws Exception {
        rejected("branchA", operation(operation, true), 403);
    }

    @Test
    void parentEmailCannotBeUsedToReadAnotherParentsChildren() throws Exception {
        rejected("parentA", get("/api/students").param("parentEmail", actors.get("parentB").getEmail()), 403);
    }

    @Test
    void aParentCanReadOwnChildrenAndTheirPaymentsAcrossBranches() throws Exception {
        http.perform(as("parentA", get("/api/students").param("parentEmail", "PARENTA@EXAMPLE.INVALID")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        http.perform(as("parentA", operation("student-payments", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].onePipePaymentId").value(paymentA.getOnePipePaymentId()));
        http.perform(as("parentA", get("/api/payments").param("studentId", childAcrossBranches.getId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].onePipePaymentId")
                        .value(paymentAcrossBranches.getOnePipePaymentId()));
        verifyNoInteractions(provider);
    }

    @Test
    void branchParentEmailSearchIsRestrictedEvenWhenTheParentHasChildrenInTwoBranches() throws Exception {
        http.perform(as("branchA", get("/api/students").param("parentEmail", actors.get("parentA").getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(childA.getId().intValue()));
        rejected("branchA", get("/api/students").param("parentEmail", actors.get("parentB").getEmail()), 404);
    }

    @Test
    void theSecondTenantRetainsAccessToItsOwnRecords() throws Exception {
        http.perform(as("parentB", operation("student-payments", true))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].onePipePaymentId").value(paymentB.getOnePipePaymentId()));
        http.perform(as("branchB", operation("branch-students", true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        http.perform(as("branchB", get("/api/payments").param("studentId", childAcrossBranches.getId().toString())))
                .andExpect(status().isOk());
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"parentA", "branchA", "super"})
    void authorizedCallersCanCreateQueryAndCancelAnInvoice(String caller) throws Exception {
        http.perform(as(caller, operation("invoice", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("ChildA Demo"))
                .andExpect(jsonPath("$.paymentDetails.amount").value(125));
        assertThat(payments.count()).isEqualTo(4);
        http.perform(as(caller, operation("query", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        http.perform(as(caller, operation("cancel", false))).andExpect(status().isOk());
        assertThat(payments.findById(paymentA.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        // The persistent lifecycle is authoritative; metadata in the adapter is not a second financial store.
        assertThat(provider.queryTransaction(paymentA.getTransactionRef()).getStatus()).isEqualTo("Pending");
    }

    @ParameterizedTest
    @MethodSource("administrativePlanChanges")
    void administratorsCannotChangeAnOwnBranchStudentsPlanWithOrWithoutBanking(
            String caller, PaymentType type, boolean supplyBanking) throws Exception {
        SwitchPlanRequest request = plan();
        request.setNewPaymentType(type);
        request.setFrequency(InstallmentFrequency.WEEKLY);
        request.setNumberOfInstallments(4);
        if (supplyBanking) {
            request.setBankAccountNumber("DEMO-REPLACEMENT"); request.setBankCode("DEMO-REPLACEMENT-BANK");
        }
        String providerBefore = providerSnapshot();
        clearInvocations(provider);
        rejected(caller, put("/api/payments/students/{id}/switch-plan", childA.getId()).contentType("application/json")
                .content(json.writeValueAsString(request)), 403);
        assertThat(providerSnapshot()).isEqualTo(providerBefore);
    }

    static Stream<Arguments> administrativePlanChanges() {
        return Stream.of("branchA", "super").flatMap(caller -> Arrays.stream(PaymentType.values())
                .flatMap(type -> Stream.of(false, true).map(banking -> Arguments.of(caller, type, banking))));
    }

    @ParameterizedTest
    @EnumSource(PaymentType.class)
    void anOwningParentCanChangeTheirChildsPlanUsingStoredBanking(PaymentType type) throws Exception {
        SwitchPlanRequest request = plan(); request.setNewPaymentType(type);
        request.setFrequency(InstallmentFrequency.WEEKLY); request.setNumberOfInstallments(4);
        http.perform(as("parentA", put("/api/payments/students/{id}/switch-plan", childA.getId())
                .contentType("application/json").content(json.writeValueAsString(request)))).andExpect(status().isOk());
        Student changed = students.findById(childA.getId()).orElseThrow();
        assertThat(changed.getPreferredPaymentType()).isEqualTo(type);
        assertThat(changed.getInstallmentFrequency()).isEqualTo(type != PaymentType.SINGLE_PAYMENT ? InstallmentFrequency.WEEKLY : null);
        assertThat(changed.getNumberOfInstallments()).isEqualTo(type == PaymentType.INSTALLMENT ? 4 : null);
        assertThat(parents.findById(parentA.getId()).orElseThrow().getBankAccountNumber()).isEqualTo(parentA.getBankAccountNumber());
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"branchA", "super"})
    void directAdministrativeServiceCallsCannotChangePlans(String caller) throws Exception {
        UserDetails principal = details(actors.get(caller));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        List<?> before = snapshot(); String providerBefore = providerSnapshot(); clearInvocations(provider);
        assertThatThrownBy(() -> paymentService.updatePaymentPlan(childA.getId(), plan()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
        assertThat(providerSnapshot()).isEqualTo(providerBefore);
    }

    @Test
    void onlyTheOwningParentCanSupplyReplacementBankingDuringAPlanChange() throws Exception {
        SwitchPlanRequest request = plan(); request.setBankAccountNumber("DEMO-OWNER-ACCOUNT"); request.setBankCode("DEMO-OWNER-BANK");
        http.perform(as("parentA", put("/api/payments/students/{id}/switch-plan", childA.getId())
                .contentType("application/json").content(json.writeValueAsString(request)))).andExpect(status().isOk());
        assertThat(parents.findById(parentA.getId()).orElseThrow().getBankAccountNumber()).isEqualTo("DEMO-OWNER-ACCOUNT");
        assertThat(parents.findById(parentB.getId()).orElseThrow().getBankAccountNumber()).isEqualTo(parentB.getBankAccountNumber());
        verifyNoInteractions(provider);
    }

    @Test
    void branchAndSuperAdminLegitimateListingsRemainAvailable() throws Exception {
        http.perform(as("branchA", operation("branch-students", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        http.perform(as("branchA", operation("branch-payments", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        http.perform(as("super", operation("branch-students", true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        http.perform(as("super", operation("branch-payments", true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        http.perform(as("super", get("/api/super-admin/stats"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBranches").value(2));
        http.perform(as("super", operation("student-payments", true))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"parentA", "branchA"})
    void nonSuperAdministratorsCannotUsePlatformAdministration(String caller) throws Exception {
        rejected(caller, get("/api/super-admin/stats"), 403);
        rejected(caller, post("/api/branches").contentType("application/json").content("{}"), 403);
    }

    @Test
    void aSuperAdminCanStillCreateABranch() throws Exception {
        CreateBranchRequest request = CreateBranchRequest.builder().adminEmail("new-branch@example.invalid")
                .adminPassword(UUID.randomUUID().toString()).businessName("New Demo Campus").businessShortName("DEMO-NEW")
                .address("Example City").rcNumber(UUID.randomUUID().toString()).contactFirstName("Demo")
                .contactSurname("Administrator").whatsappNumber("DEMO-PHONE").build();
        http.perform(as("super", post("/api/branches").contentType("application/json").content(json.writeValueAsString(request))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.businessName").value("New Demo Campus"))
                .andExpect(jsonPath("$.adminEmail").value("new-branch@example.invalid"));
        assertThat(branches.count()).isEqualTo(3);
    }

    @Test
    void publicDiscoveryHidesPrivateFieldsAndAdministrativeViewsAreScoped() throws Exception {
        http.perform(get("/api/branches")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].businessName").value("Campus A"))
                .andExpect(jsonPath("$[0].address").value("Example City"))
                .andExpect(jsonPath("$[0].adminEmail").isEmpty()).andExpect(jsonPath("$[0].billerCode").isEmpty())
                .andExpect(jsonPath("$[0].phoneNumber").isEmpty()).andExpect(jsonPath("$[0].contactPersonName").isEmpty());
        http.perform(as("parentA", get("/api/branches"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adminEmail").isEmpty());
        http.perform(as("branchA", get("/api/branches"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adminEmail").value(actors.get("branchA").getEmail()))
                .andExpect(jsonPath("$[1].adminEmail").isEmpty());
        http.perform(as("super", get("/api/branches"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].adminEmail").value(actors.get("branchB").getEmail()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"student-payments", "invoice", "query", "cancel", "plan", "branch-students", "branch-payments"})
    void sensitiveOperationsRequireAuthentication(String operation) throws Exception {
        rejected(null, operation(operation, false), 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"parentA", "branchA"})
    void historicalDiagnosticsAreRestrictedAtTheSecurityBoundary(String caller) throws Exception {
        rejected(caller, get("/api/test/encrypt"), 403);
        rejected(caller, get("/api/test/onepipe-connection"), 403);
    }

    @Test
    void mockModeDoesNotExposeDiagnosticsAndNoPaymentTestEndpointIsPublic() throws Exception {
        http.perform(as("super", get("/api/test/encrypt"))).andExpect(status().isNotFound());
        rejected(null, get("/api/payments/test/anything"), 401);
        rejected(null, get("/api/super-admin/stats"), 401);
        rejected(null, post("/api/branches").contentType("application/json").content("{}"), 401);
    }

    @Test
    void invalidAuthenticationCannotDowngradeRegistrationToAnonymousSignup() throws Exception {
        List<?> before = snapshot();
        http.perform(registrationRequest(registration()).header("Authorization", "Bearer invalid-demo-token"))
                .andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "expired", "bad-signature", "unknown-user", "missing-subject"})
    void invalidTokensReturnASafe401(String kind) throws Exception {
        String token;
        if (kind.equals("malformed")) {
            token = "not-a-valid-jwt";
        } else {
            token = Jwts.builder().setSubject(kind.equals("missing-subject") ? null
                            : kind.equals("unknown-user") ? "unknown@example.invalid" : actors.get("parentA").getEmail())
                    .setExpiration(Date.from(Instant.now().plusSeconds(kind.equals("expired") ? -60 : 600)))
                    .signWith(Keys.hmacShaKeyFor(kind.equals("bad-signature") ? randomKey() : JWT_KEY), SignatureAlgorithm.HS256).compact();
        }
        List<?> before = snapshot();
        http.perform(get("/api/payments").param("studentId", childA.getId().toString()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
    }

    @Test
    void signedRoleAndBranchClaimsCannotOverrideDatabaseRelationships() throws Exception {
        String token = jwt.generateToken(Map.of("role", "SUPER_ADMIN", "branchId", branchB.getId()), details(actors.get("parentA")));
        http.perform(operation("branch-payments", true).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARENT", "BRANCH_ADMIN"})
    void missingAssociationsFailClosed(String role) throws Exception {
        actor("orphan", Role.valueOf(role));
        rejected("orphan", operation("student-payments", false), 403);
    }

    @Test
    void missingResourcesReturnGeneric404Errors() throws Exception {
        rejected("parentA", get("/api/payments").param("studentId", Long.toString(Long.MAX_VALUE)), 404);
        rejected("super", post("/api/payments/unknown-demo-id/query"), 404);
    }

    @Test
    void existingPaymentStateConflictsReturn409WithoutSideEffects() throws Exception {
        paymentA.setCategory(PaymentCategory.SCHOOL_FEES); paymentA = payments.save(paymentA);
        rejected("parentA", operation("plan", false), 409);
        rejected("parentA", post("/api/payments/new").contentType("application/json").content(json.writeValueAsString(
                Map.of("studentId", childA.getId(), "category", "SCHOOL_FEES", "amount", 125,
                        "paymentType", "SINGLE_PAYMENT"))), 409);
    }

    @Test
    void duplicateBranchAdministratorIdentityReturns409WithoutProviderCalls() throws Exception {
        CreateBranchRequest request = CreateBranchRequest.builder().adminEmail(actors.get("parentA").getEmail())
                .adminPassword(UUID.randomUUID().toString()).build();
        rejected("super", post("/api/branches").contentType("application/json").content(json.writeValueAsString(request)), 409);
    }

    @Test
    void inconsistentPaymentRelationshipsFailClosedBeforeProviderAccess() throws Exception {
        paymentA.setParent(parentB); paymentA = payments.save(paymentA);
        rejected("parentA", operation("query", false), 403);
        rejected("branchA", operation("student-payments", false), 403);
        rejected("super", operation("cancel", false), 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"existing-email", "existing-phone", "administrator-email"})
    void publicRegistrationCannotReuseAnExistingIdentity(String collision) throws Exception {
        RegisterStudentRequest request = registration();
        if (collision.equals("existing-email")) request.setParentEmail(actors.get("parentB").getEmail().toUpperCase(Locale.ROOT));
        if (collision.equals("existing-phone")) request.setParentPhoneNumber(parentB.getPhoneNumber());
        if (collision.equals("administrator-email")) request.setParentEmail(actors.get("super").getEmail());
        rejected(null, registrationRequest(request), 409);
    }

    @Test
    void publicRegistrationCreatesOnlyANewParentAndPreservesTheResponseContract() throws Exception {
        RegisterStudentRequest request = registration();
        http.perform(registrationRequest(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentRegId").isString()).andExpect(jsonPath("$.paymentDetails.onePipePaymentId").isString())
                .andExpect(jsonPath("$.paymentDetails.amount").value(100000));
        User created = users.findByEmailIgnoreCase(request.getParentEmail()).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.PARENT);
        assertThat(encoder.matches(request.getParentPassword(), created.getPassword())).isTrue();
        assertThat(students.count()).isEqualTo(4); assertThat(parents.count()).isEqualTo(3);
    }

    @Test
    void authenticatedParentsCanAddAChildWithoutReplacingTheirIdentityPasswordOrBanking() throws Exception {
        RegisterStudentRequest request = ownedRegistration(); request.setParentPassword(UUID.randomUUID().toString());
        request.setParentFirstName("Ignored Demo Replacement");
        String originalHash = actors.get("parentA").getPassword();
        http.perform(as("parentA", registrationRequest(request))).andExpect(status().isOk());
        assertThat(parents.count()).isEqualTo(2); assertThat(students.count()).isEqualTo(4);
        assertThat(users.findById(actors.get("parentA").getId()).orElseThrow().getPassword()).isEqualTo(originalHash);
        Parent retained = parents.findById(parentA.getId()).orElseThrow();
        assertThat(retained.getFirstName()).isEqualTo(parentA.getFirstName());
        assertThat(retained.getBankAccountNumber()).isEqualTo(parentA.getBankAccountNumber());
        assertThat(students.findByParent(parentA)).hasSize(3);
    }

    @Test
    void registrationCannotReplaceEvenAnAuthenticatedParentsBanking() throws Exception {
        RegisterStudentRequest request = ownedRegistration(); request.setBankAccountNumber("DEMO-CHANGED");
        rejected("parentA", registrationRequest(request), 409);
    }

    @Test
    void authenticatedParentsCannotRegisterUsingAnotherOrNewParentIdentity() throws Exception {
        RegisterStudentRequest request = ownedRegistration(); request.setParentEmail(actors.get("parentB").getEmail());
        rejected("parentA", registrationRequest(request), 403);
        rejected("parentA", registrationRequest(registration()), 403);
        request = ownedRegistration(); request.setParentPhoneNumber(parentB.getPhoneNumber());
        rejected("parentA", registrationRequest(request), 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"branchA", "super"})
    void administratorsCannotAttachChildrenToExistingParents(String caller) throws Exception {
        rejected(caller, registrationRequest(ownedRegistration()), 409);
    }

    @Test
    void branchAdministratorsCanRegisterNewParentsOnlyInTheirOwnBranch() throws Exception {
        RegisterStudentRequest request = registration(); request.setBranchId(branchB.getId());
        rejected("branchA", registrationRequest(request), 403);
        request.setBranchId(branchA.getId());
        http.perform(as("branchA", registrationRequest(request))).andExpect(status().isOk());
    }

    @Test
    void loginStillReturnsTheExistingContractAndInvalidCredentialsReturn401() throws Exception {
        http.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", actors.get("parentA").getEmail(), "password", passwords.get("parentA")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.role").value("PARENT"));
        http.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", actors.get("parentA").getEmail(), "password", UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void serviceCallsAreProtectedEvenWithoutControllers() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                details(actors.get("parentA")), null, details(actors.get("parentA")).getAuthorities()));
        List<?> before = snapshot();
        assertThatThrownBy(() -> paymentService.triggerNewPayment(childB.getId(), PaymentCategory.ADHOC_PAYMENT,
                BigDecimal.TEN, PaymentType.SINGLE_PAYMENT, "Demo")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.cancelSubscription(paymentB.getOnePipePaymentId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.queryAndFixPaymentStatus(paymentB.getOnePipePaymentId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.updatePaymentPlan(childB.getId(), plan())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> studentService.getStudentsByBranch(branchA.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> branchService.createBranch(new CreateBranchRequest())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(dashboardService::getSuperAdminStats).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> paymentService.getPaymentsForChild(childA.getId()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
    }

    private void rejected(String caller, MockHttpServletRequestBuilder request, int status) throws Exception {
        List<?> before = snapshot();
        http.perform(caller == null ? request : as(caller, request)).andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.message").isString());
        assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(provider);
    }

    private MockHttpServletRequestBuilder operation(String name, boolean foreign) throws Exception {
        Student child = foreign ? childB : childA; Payment payment = foreign ? paymentB : paymentA;
        switch (name) {
            case "student-payments": return get("/api/payments").param("studentId", child.getId().toString());
            case "branch-students": return get("/api/students").param("branchId", (foreign ? branchB : branchA).getId().toString());
            case "branch-payments": return get("/api/payments").param("branchId", (foreign ? branchB : branchA).getId().toString());
            case "query": return post("/api/payments/{id}/query", payment.getOnePipePaymentId());
            case "cancel": return post("/api/payments/{id}/cancel", payment.getOnePipePaymentId());
            case "plan": return put("/api/payments/students/{id}/switch-plan", child.getId())
                    .contentType("application/json").content(json.writeValueAsString(plan()));
            case "invoice": return post("/api/payments/new").contentType("application/json").content(json.writeValueAsString(
                    Map.of("studentId", child.getId(), "category", "ADHOC_PAYMENT", "amount", 125,
                            "paymentType", "SINGLE_PAYMENT", "description", "Synthetic invoice")));
            default: throw new IllegalArgumentException("Unknown test operation");
        }
    }

    private MockHttpServletRequestBuilder as(String caller, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(details(actors.get(caller))));
    }

    private UserDetails details(User user) {
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword()).roles(user.getRole().name()).build();
    }

    private SwitchPlanRequest plan() {
        SwitchPlanRequest request = new SwitchPlanRequest(); request.setNewPaymentType(PaymentType.SUBSCRIPTION);
        request.setFrequency(InstallmentFrequency.MONTHLY); return request;
    }

    private RegisterStudentRequest registration() {
        return RegisterStudentRequest.builder().branchId(branchA.getId()).firstName("NewChild").surname("Demo")
                .classGrade(ClassGrade.JSS1).dateOfBirth(java.time.LocalDate.of(2012, 1, 1))
                .parentFirstName("New").parentSurname("Parent").parentTitle(Title.MR)
                .parentEmail("new-" + UUID.randomUUID() + "@example.invalid").parentPassword(UUID.randomUUID().toString())
                .parentPhoneNumber("DEMO-" + UUID.randomUUID()).paymentType(PaymentType.SINGLE_PAYMENT).build();
    }

    private RegisterStudentRequest ownedRegistration() {
        RegisterStudentRequest request = registration(); request.setParentEmail(actors.get("parentA").getEmail());
        request.setParentPhoneNumber(parentA.getPhoneNumber()); return request;
    }

    private MockHttpServletRequestBuilder registrationRequest(RegisterStudentRequest request) throws Exception {
        return post("/api/students/register").contentType("application/json").content(json.writeValueAsString(request));
    }

    private User actor(String name, Role role) {
        String password = UUID.randomUUID().toString(); passwords.put(name, password);
        User user = users.save(User.builder().email(name.toLowerCase(Locale.ROOT) + "@example.invalid")
                .password(encoder.encode(password)).role(role).build()); actors.put(name, user); return user;
    }

    private Branch branch(User admin, String label) {
        return branches.save(Branch.builder().adminUser(admin).businessName("Campus " + label).businessShortName("DEMO-" + label)
                .businessAddress("Example City").billerCode("MOCK-MERCHANT-" + label).contactFirstName("Demo")
                .contactSurname("Administrator").whatsappNumber("DEMO-CONTACT-" + label).build());
    }

    private Parent parent(User user, String label) {
        return parents.save(Parent.builder().user(user).title(Title.MR).firstName("Parent" + label).surname("Demo")
                .phoneNumber("DEMO-PHONE-" + label).bankAccountNumber("DEMO-ACCOUNT-" + label).bankCode("DEMO-BANK").build());
    }

    private Student child(Parent parent, Branch branch, String name) {
        return students.save(Student.builder().firstName(name).surname("Demo").studentRegId(UUID.randomUUID().toString())
                .classGrade(ClassGrade.JSS1).branch(branch).parent(parent).preferredPaymentType(PaymentType.SINGLE_PAYMENT).build());
    }

    private Payment payment(Student student) {
        OnePipeInvoiceRequest invoice = new OnePipeInvoiceRequest(); invoice.setRequestRef(UUID.randomUUID().toString());
        OnePipeInvoiceRequest.Transaction tx = new OnePipeInvoiceRequest.Transaction(); tx.setTransactionRef(UUID.randomUUID().toString());
        OnePipeInvoiceRequest.Meta meta = new OnePipeInvoiceRequest.Meta(); meta.setType("subscription"); tx.setMeta(meta); invoice.setTransaction(tx);
        OnePipeResponse result = provider.sendInvoice(invoice);
        return payments.save(Payment.builder().student(student).parent(student.getParent()).branch(student.getBranch())
                .category(PaymentCategory.ADHOC_PAYMENT).paymentType(PaymentType.SUBSCRIPTION).status(PaymentStatus.PENDING)
                .totalAmount(new BigDecimal("100.00")).amountPerCycle(new BigDecimal("100.00")).completedPayments(0).accountingVersion(1)
                .description("Synthetic payment").requestRef(invoice.getRequestRef()).transactionRef(tx.getTransactionRef())
                .onePipePaymentId(result.getPaymentId()).virtualAccountNumber(result.getVirtualAccountNumber()).build());
    }

    private List<?> snapshot() {
        return Stream.of("app_users", "parents", "branches", "students", "payments")
                .map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    private String providerSnapshot() throws Exception {
        return json.writeValueAsString(List.of(provider.queryTransaction(paymentA.getTransactionRef()),
                provider.queryTransaction(paymentB.getTransactionRef()),
                provider.queryTransaction(paymentAcrossBranches.getTransactionRef())));
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key;
    }

    static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
