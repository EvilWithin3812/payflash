package com.payflash.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflash.auth.AuthUser;
import com.payflash.common.ApiError;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs inside the security chain right after {@code JwtAuthFilter}, so the user is already known.
 * Not a {@code @Component}: that would also register it as a plain servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties props, ObjectMapper mapper, MeterRegistry metrics) {
        this.limiter = limiter;
        this.props = props;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var policy = RateLimitPolicy.resolve(request);
        if (policy.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        RateLimitPolicy p = policy.get();
        RateLimiter.Decision decision = limiter.tryConsume(p.bucketKey(request, currentUser()), p.perMinute(props));

        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        metrics.counter("payflash.ratelimit.rejected", "policy", p.name().toLowerCase()).increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of(429, "RATE_LIMITED",
                "Too many requests. Try again in " + decision.retryAfterSeconds() + "s"));
    }

    private static AuthUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser user ? user : null;
    }
}
