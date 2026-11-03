package com.onepipe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class CorsPolicyConfiguration {

    private static final List<String> LOCAL_ORIGINS = List.of(
            "http://localhost:5173", "http://127.0.0.1:5173");
    private final Environment environment;

    public CorsPolicyConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins(environment));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // Also called by the early environment validator, before datasource initialization.
    static List<String> allowedOrigins(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1) {
            throw new IllegalStateException("CORS requires exactly one supported profile: dev, test, or hosted.");
        }
        if ("dev".equals(profiles[0]) || "test".equals(profiles[0])) {
            return LOCAL_ORIGINS;
        }
        if (!"hosted".equals(profiles[0])) {
            throw new IllegalStateException("CORS requires exactly one supported profile: dev, test, or hosted.");
        }

        // The environment setting is mandatory even if an effective-property override exists.
        List<String> origins = parseHostedOrigins(setting(environment, "CORS_ALLOWED_ORIGINS"));
        if (!origins.equals(parseHostedOrigins(setting(environment, "app.cors.allowed-origins")))) {
            throw new IllegalStateException("The hosted CORS policy must match CORS_ALLOWED_ORIGINS.");
        }
        return origins;
    }

    private static String setting(Environment environment, String name) {
        try {
            return environment.getProperty(name);
        } catch (IllegalArgumentException exception) {
            // Placeholder errors can include configured values; do not retain the cause.
            throw invalidHostedOrigins();
        }
    }

    private static List<String> parseHostedOrigins(String value) {
        if (value == null || value.isBlank()) {
            throw invalidHostedOrigins();
        }
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String entry : value.split(",", -1)) {
            String origin = entry.trim();
            try {
                URI uri = new URI(origin);
                int port = uri.getPort();
                String authority = uri.getHost() + (port == -1 ? "" : ":" + port);
                if (origin.isEmpty() || origin.contains("*") || uri.isOpaque()
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || port < -1 || port == 0 || port > 65535
                        || !authority.equalsIgnoreCase(uri.getRawAuthority())) {
                    throw invalidHostedOrigins();
                }
            } catch (URISyntaxException exception) {
                throw invalidHostedOrigins();
            }
            origins.add(origin);
        }
        return List.copyOf(origins);
    }

    private static IllegalStateException invalidHostedOrigins() {
        return new IllegalStateException("The hosted profile requires CORS_ALLOWED_ORIGINS to contain a nonempty "
                + "comma-separated list of exact HTTP/HTTPS origins, without wildcards, credentials, paths, "
                + "queries, fragments, or invalid ports.");
    }
}
