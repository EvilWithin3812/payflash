package com.payflash.config;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the trace id as X-Trace-Id on every response. Paste it into Zipkin to see the request's full
 * path (gateway → app → Kafka → notification-service), or grep the logs for it.
 */
@Component
public class TraceIdHeaderFilter extends OncePerRequestFilter {

    private final ObjectProvider<Tracer> tracer;

    public TraceIdHeaderFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tracer t = tracer.getIfAvailable();
        if (t != null && t.currentSpan() != null) {
            response.setHeader("X-Trace-Id", t.currentSpan().context().traceId());
        }
        chain.doFilter(request, response);
    }
}
