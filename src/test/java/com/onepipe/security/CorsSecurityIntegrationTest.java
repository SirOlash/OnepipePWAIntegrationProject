package com.onepipe.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepipe.config.CorsPolicyConfiguration;
import com.onepipe.config.JwtAuthenticationFilter;
import com.onepipe.config.JwtService;
import com.onepipe.config.SecurityConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Runs the application's real security and CORS filter chain without a database. */
class CorsSecurityIntegrationTest {
    private static final String FRONTEND = "https://greenfieldboardingschool.vercel.app";

    @Test
    void hostedAcceptsOnlyTheExplicitFrontendWithoutCredentialedCors() throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(get("/api/branches").header(HttpHeaders.ORIGIN, FRONTEND))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                    .andExpect(content().string("public branches"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://unknown.example",
            "https://another-project.vercel.app",
            "https://preview.greenfieldboardingschool.vercel.app",
            "https://greenfieldboardingschool.vercel.app.evil.example",
            "https://greenfieldboardingschool-vercel.app",
            "http://greenfieldboardingschool.vercel.app",
            "https://greenfieldboardingschool.vercel.app:8443",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "null"
    })
    void hostedRejectsUnknownOriginsAndVercelLookalikes(String origin) throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(get("/api/branches").header(HttpHeaders.ORIGIN, origin))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "OPTIONS"})
    void preflightAcceptsExistingMethodsAndJwtHeadersWithoutAuthentication(String method) throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(options("/api/private")
                            .header(HttpHeaders.ORIGIN, FRONTEND)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString(method)))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                            containsString("authorization")))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                            containsString("content-type")))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    @Test
    void preflightRejectsUnknownOriginsBeforeAuthentication() throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(options("/api/private")
                            .header(HttpHeaders.ORIGIN, "https://another-project.vercel.app")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void preflightDoesNotAuthorizeUnconfiguredMethodsOrHeaders() throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(options("/api/private")
                            .header(HttpHeaders.ORIGIN, FRONTEND)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                    .andExpect(status().isForbidden());
            app.http.perform(options("/api/private")
                            .header(HttpHeaders.ORIGIN, FRONTEND)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Unconfigured-Header"))
                    .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @MethodSource("localOrigins")
    void devAndTestAcceptBothLocalFrontendOrigins(String profile, String origin) throws Exception {
        try (Application app = application(profile)) {
            app.http.perform(get("/api/branches").header(HttpHeaders.ORIGIN, origin))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
            app.http.perform(options("/api/private")
                            .header(HttpHeaders.ORIGIN, origin)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    static Stream<Arguments> localOrigins() {
        return Stream.of("dev", "test").flatMap(profile ->
                Stream.of("http://localhost:5173", "http://127.0.0.1:5173")
                        .map(origin -> Arguments.of(profile, origin)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void localProfilesDoNotAcceptOtherOrigins(String profile) throws Exception {
        try (Application app = application(profile)) {
            app.http.perform(get("/api/branches").header(HttpHeaders.ORIGIN, "http://localhost:5174"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void allowedOriginDoesNotBypassAuthenticationAndReceivesTheSafeAuthenticationError() throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(get("/api/private").header(HttpHeaders.ORIGIN, FRONTEND))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test
    void allowedOriginPreservesJwtAuthenticationAndRoleRestrictions() throws Exception {
        try (Application app = application("hosted")) {
            String token = app.context.getBean(JwtService.class).generateToken(syntheticParent());
            app.http.perform(get("/api/private").header(HttpHeaders.ORIGIN, FRONTEND)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
            app.http.perform(get("/api/test/cors").header(HttpHeaders.ORIGIN, FRONTEND)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @Test
    void requestsWithoutOriginKeepExistingPublicAndProtectedBehavior() throws Exception {
        try (Application app = application("hosted")) {
            app.http.perform(get("/api/branches"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            app.http.perform(get("/api/private"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    private static Application application(String profile) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.withProperty("security.jwt.secret-key", Base64.getEncoder().encodeToString(key))
                .withProperty("security.jwt.expiration", "60000")
                .withProperty("test.cors.isolated-security-fixture", "true");
        if (profile.equals("hosted")) {
            environment.withProperty("CORS_ALLOWED_ORIGINS", FRONTEND)
                    .withProperty("app.cors.allowed-origins", FRONTEND);
        }
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setEnvironment(environment);
        context.setServletContext(new MockServletContext());
        context.register(WebConfiguration.class);
        context.refresh();
        MockMvc http = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        return new Application(context, http);
    }

    private static UserDetails syntheticParent() {
        return User.withUsername("cors-parent@example.invalid").password("unused").roles("PARENT").build();
    }

    private record Application(AnnotationConfigWebApplicationContext context, MockMvc http)
            implements AutoCloseable {
        @Override public void close() { context.close(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.cors.isolated-security-fixture", havingValue = "true")
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsPolicyConfiguration.class, JwtAuthenticationFilter.class,
            SecurityErrorHandler.class, Endpoints.class})
    static class WebConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AuthenticationProvider authenticationProvider() { return mock(AuthenticationProvider.class); }
        @Bean UserDetailsService userDetailsService() { return username -> syntheticParent(); }
        @Bean JwtService jwtService() { return new JwtService(); }
    }

    @TestComponent
    @RestController
    @ConditionalOnProperty(name = "test.cors.isolated-security-fixture", havingValue = "true")
    static class Endpoints {
        @GetMapping("/api/branches") String publicBranches() { return "public branches"; }
        @GetMapping("/api/private") String privateResource() { return "private resource"; }
        @GetMapping("/api/test/cors") String administratorResource() { return "administrator resource"; }
    }
}
