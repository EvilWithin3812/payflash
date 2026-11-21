package com.payflash.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.auth.JwtAuthFilter;
import com.payflash.common.ApiError;
import com.payflash.ratelimit.RateLimitFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter, ObjectMapper mapper,
                                            ObjectProvider<RateLimitFilter> rateLimitFilter) throws Exception {
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        // Rate limiting runs after JWT parsing so it can key buckets by user id.
        rateLimitFilter.ifAvailable(f -> http.addFilterAfter(f, JwtAuthFilter.class));
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Razorpay calls this server-to-server; it is authenticated by its HMAC signature instead.
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                        .requestMatchers("/", "/razorpay-test.html").permitAll()
                        // Sale catalogue and live stock are public; reserving and paying are not.
                        .requestMatchers(HttpMethod.GET, "/api/v1/sales", "/api/v1/sales/{saleId:\\d+}").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/demo").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Moving money needs a verified email: stops throwaway accounts from holding sale stock.
                        .requestMatchers(HttpMethod.POST, "/api/v1/wallet/topup", "/api/v1/wallet/transfer",
                                "/api/v1/payments/**", "/api/v1/sales/**")
                        .hasAuthority(JwtAuthFilter.EMAIL_VERIFIED)
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            mapper.writeValue(res.getOutputStream(),
                                    ApiError.of(401, "UNAUTHORIZED", "Authentication required"));
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            ApiError body = needsVerifiedEmail(req)
                                    ? ApiError.of(403, "EMAIL_NOT_VERIFIED",
                                            "Please verify your email address first. Check your inbox for the link")
                                    : ApiError.of(403, "FORBIDDEN", "Access denied");
                            mapper.writeValue(res.getOutputStream(), body);
                        }))
                .build();
    }

    private static boolean needsVerifiedEmail(jakarta.servlet.http.HttpServletRequest req) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && !req.getRequestURI().startsWith("/api/v1/admin/")
                && auth.getAuthorities().stream().noneMatch(a -> JwtAuthFilter.EMAIL_VERIFIED.equals(a.getAuthority()));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
