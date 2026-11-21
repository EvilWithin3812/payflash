package com.payflash.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries the trace across the outbox's asynchronous gap. When an event is written, the current W3C
 * {@code traceparent} is stored with it; when the publisher later sends it, that context becomes the parent
 * of a "outbox publish" span, so the Kafka producer and every consumer join the original request's trace.
 * A no-op when tracing is disabled (e.g. in tests).
 */
@Component
public class OutboxTracing {

    private static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public OutboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current request's traceparent, or null when there is no active trace. */
    public String currentTraceParent() {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || t.currentTraceContext().context() == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        p.inject(t.currentTraceContext().context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs {@code action} inside a span that continues the stored trace (or just runs it). */
    public <T> T continueTrace(String traceParent, String spanName, Supplier<T> action) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || traceParent == null) {
            return action.get();
        }
        Span span = p.extract(Map.of(TRACEPARENT, traceParent), Map::get)
                .name(spanName).kind(Span.Kind.PRODUCER).start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return action.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
