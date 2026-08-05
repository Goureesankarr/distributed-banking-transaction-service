package com.bank.txn.messaging;

import com.bank.txn.domain.OutboxEvent;
import com.bank.txn.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Appends an event to the outbox using the caller's transaction. If the
 * business transaction rolls back the event disappears with it, which is the
 * whole point: no event without a committed state change.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public void record(String aggregateType, String aggregateId, String eventType, Object payload) {
        outbox.save(new OutboxEvent(aggregateType, aggregateId, eventType, toJson(payload)));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise outbox payload", e);
        }
    }
}
