package com.payflash.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads "Authorization: Bearer <token>" and populates the SecurityContext. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Granted when the user's email is verified (or verification is not required). Money endpoints need it. */
    public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final TokenRevocations revocations;
    private final boolean verificationRequired;

    public JwtAuthFilter(JwtService jwtService, TokenRevocations revocations,
                         @Value("${app.auth.require-verified-email:true}") boolean verificationRequired) {
        this.jwtService = jwtService;
        this.revocations = revocations;
        this.verificationRequired = verificationRequired;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.parseAccessToken(header.substring(BEARER.length()))
                    .filter(user -> !revocations.isRevoked(user))
                    .ifPresent(user -> {
                        List<GrantedAuthority> authorities = new ArrayList<>();
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
                        if (user.emailVerified() || !verificationRequired) {
                            authorities.add(new SimpleGrantedAuthority(EMAIL_VERIFIED));
                        }
                        SecurityContextHolder.getContext()
                                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
                    });
        }
        chain.doFilter(request, response);
    }
}
