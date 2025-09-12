package net.kyver.placy.config;

import net.kyver.placy.filter.SecretKeyValidationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${placy.security.api-key.enabled:true}")
    private boolean apiKeyEnabled;

    @Value("${placy.security.cors.enabled:true}")
    private boolean corsEnabled;

    @Value("${placy.security.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    @Value("${SECRET_KEY:}")
    private String secretKey;

    private final SecretKeyValidationFilter secretKeyValidationFilter;

    public SecurityConfig(SecretKeyValidationFilter secretKeyValidationFilter) {
        this.secretKeyValidationFilter = secretKeyValidationFilter;
    }

    private boolean isSecurityEnabled() {
        return secretKey != null && !secretKey.trim().isEmpty() && !secretKey.equals("your_secret_key_here");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .cors(cors -> {
                if (corsEnabled) {
                    cors.configurationSource(corsConfigurationSource());
                } else {
                    cors.disable();
                }
            });

        if (isSecurityEnabled()) {
            // Apply security when SECRET_KEY is configured
            http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/health", "/info", "/favicon.ico").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v2/metrics").authenticated()
                        .requestMatchers("/api/v2/supported-types").permitAll()
                        .requestMatchers("/api/v2/validate").authenticated()
                        .requestMatchers("/api/v2/transform/**").authenticated()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(secretKeyValidationFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            // Allow all requests when no SECRET_KEY is configured
            http.authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .addFilterBefore(secretKeyValidationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        http.headers(headers -> headers
                    .frameOptions(frameOptions -> frameOptions.deny())
                    .contentTypeOptions(contentTypeOptions -> {})
                    .httpStrictTransportSecurity(hstsConfig -> hstsConfig
                        .maxAgeInSeconds(31536000)
                        .includeSubDomains(true))
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        String allowedOrigins = System.getProperty("placy.cors.allowed-origins", "*");
        if ("*".equals(allowedOrigins)) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        }

        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"
        ));

        configuration.setAllowedHeaders(Arrays.asList(
            "Origin", "Content-Type", "Accept", "Authorization",
            "X-Requested-With", "X-API-Key", "X-Client-Version"
        ));

        configuration.setExposedHeaders(Arrays.asList(
            "X-Processing-Time-Ms", "X-Throughput-MBps", "X-Replacements-Made",
            "X-Files-Processed", "X-Total-Replacements", "Content-Disposition"
        ));

        configuration.setAllowCredentials(true);

        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    @Bean
    public SecurityHeadersConfig securityHeadersConfig() {
        return new SecurityHeadersConfig();
    }

    public static class SecurityHeadersConfig {

        public String getContentSecurityPolicy() {
            return "default-src 'self'; " +
                   "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                   "style-src 'self' 'unsafe-inline'; " +
                   "img-src 'self' data: https:; " +
                   "font-src 'self' https:; " +
                   "connect-src 'self' https:; " +
                   "frame-ancestors 'none';";
        }

        public String getReferrerPolicy() {
            return "strict-origin-when-cross-origin";
        }

        public String getPermissionsPolicy() {
            return "geolocation=(), microphone=(), camera=()";
        }
    }
}
