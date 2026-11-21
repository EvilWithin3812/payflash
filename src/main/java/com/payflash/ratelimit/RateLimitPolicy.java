package com.payflash.ratelimit;

import com.payflash.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Which bucket a request is charged against. */
public enum RateLimitPolicy {
    AUTH,
    MONEY,
    API;

    /** Returns empty for requests that are never limited (webhooks, docs, health checks). */
    public static Optional<RateLimitPolicy> resolve(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return Optional.empty();
        }
        // Razorpay retries webhooks on any non-2xx: limiting them would only delay credits.
        if (path.equals("/api/v1/payments/webhook")) {
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/auth/")) {
            return Optional.of(AUTH);
        }
        if ("POST".equals(request.getMethod()) && (path.equals("/api/v1/wallet/topup")
                || path.equals("/api/v1/wallet/transfer")
                || path.startsWith("/api/v1/payments/")
                || path.startsWith("/api/v1/sales/"))) {
            return Optional.of(MONEY);
        }
        return Optional.of(API);
    }

    public int perMinute(RateLimitProperties props) {
        return switch (this) {
            case AUTH -> props.auth();
            case MONEY -> props.money();
            case API -> props.api();
        };
    }

    /**
     * Auth endpoints are always keyed by IP (there is no user yet). Others use the user id when
     * authenticated, so users behind one NAT/office IP don't share a bucket.
     * The IP is {@code getRemoteAddr()}, never a raw X-Forwarded-For header, which clients can forge;
     * behind a proxy, set {@code server.forward-headers-strategy=native} so Tomcat resolves it safely.
     */
    public String bucketKey(HttpServletRequest request, AuthUser user) {
        String subject = (this == AUTH || user == null) ? "ip:" + request.getRemoteAddr() : "user:" + user.id();
        return "rl:" + name().toLowerCase() + ":" + subject;
    }
}
