package com.payflash.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final OutboxTracing tracing;

    public OutboxService(OutboxRepository repository, ObjectMapper mapper, OutboxTracing tracing) {
        this.repository = repository;
        this.mapper = mapper;
        this.tracing = tracing;
    }

    /** Must be called inside the business transaction, so the event commits (or rolls back) with it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, String aggregateId, String eventType, String topic, Object payload) {
        try {
            repository.save(new OutboxEvent(aggregateType, aggregateId, eventType, topic,
                    mapper.writeValueAsString(payload), tracing.currentTraceParent()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }
    }
}
