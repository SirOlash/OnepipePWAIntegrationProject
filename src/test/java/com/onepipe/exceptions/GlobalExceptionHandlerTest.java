package com.onepipe.exceptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.exceptions.customExceptions.*;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private static final String PAYMENT_ID = "SYNTHETIC-PAYMENT-DO-NOT-ECHO";
    private static final String DETAIL = "SELECT * FROM payment_collections; synthetic-secret-do-not-echo " + PAYMENT_ID;
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "illegal-state", "null-pointer"})
    void unexpectedFailuresAre500WithoutMessagesCausesIdentifiersOrStackTraces(String kind) throws Exception {
        var response = http.perform(get("/errors/{kind}/{paymentId}", kind, PAYMENT_ID)
                        .queryParam("secret", "synthetic-query-secret-do-not-echo"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.path").value("/errors/{kind}/{paymentId}"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(DETAIL, PAYMENT_ID, "SELECT", "synthetic-secret", "synthetic-query-secret",
                "RuntimeException", "IllegalStateException", "NullPointerException", "stackTrace", "trace");
        Set<String> fields = new HashSet<>();
        new ObjectMapper().readTree(response).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("code", "message", "status", "timestamp", "path");
    }

    @ParameterizedTest
    @CsvSource({"validation,400,INVALID_REQUEST", "constraint,400,INVALID_REQUEST",
            "authentication,401,AUTHENTICATION_REQUIRED", "credentials,401,INVALID_CREDENTIALS",
            "authorization,403,ACCESS_DENIED", "missing,404,RESOURCE_NOT_FOUND",
            "user-missing,404,USER_NOT_FOUND", "business,409,RESOURCE_CONFLICT",
            "integrity,409,RESOURCE_CONFLICT", "concurrency,409,RESOURCE_CONFLICT"})
    void explicitExpectedHandlersKeepTheirStatusesAndStandardStructure(String kind, int expected, String code) throws Exception {
        http.perform(get("/errors/{kind}/{paymentId}", kind, PAYMENT_ID))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty()).andExpect(jsonPath("$.path").isString());
    }

    @Test
    void unexpectedFailureWithoutAMappedRouteDoesNotEchoTheConcreteRequestUri() throws Exception {
        var request = new MockHttpServletRequest("GET", "/payments/" + PAYMENT_ID);
        var response = new GlobalExceptionHandler().handleRuntimeException(new RuntimeException(DETAIL), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getPath()).isEqualTo("/");
        assertThat(new ObjectMapper().writeValueAsString(response.getBody())).doesNotContain(DETAIL, PAYMENT_ID);
    }

    @RestController
    @Profile("exception-handler-unit-test")
    static class ThrowingController {
        @GetMapping("/errors/{kind}/{paymentId}")
        public void fail(@PathVariable String kind, @PathVariable String paymentId) {
            throw switch (kind) {
                case "validation" -> new InvalidPaymentException("Invalid collection details");
                case "constraint" -> new ConstraintViolationException(Set.of());
                case "authentication" -> new AuthenticationCredentialsNotFoundException(DETAIL);
                case "credentials" -> new BadCredentialsException(DETAIL);
                case "authorization" -> new AccessDeniedException(DETAIL);
                case "missing" -> new ResourceNotFoundException();
                case "user-missing" -> new UserNotFoundException("User not found");
                case "business" -> new ResourceConflictException("Collection conflicts with recorded state");
                case "integrity" -> new DataIntegrityViolationException(DETAIL);
                case "concurrency" -> new ConcurrencyFailureException(DETAIL);
                case "illegal-state" -> new IllegalStateException(DETAIL, new RuntimeException(DETAIL));
                case "null-pointer" -> new NullPointerException(DETAIL);
                default -> new RuntimeException(DETAIL, new RuntimeException(DETAIL));
            };
        }
    }
}
