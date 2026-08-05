package com.bank.txn.messaging;

import com.bank.txn.domain.AuditLog;
import com.bank.txn.domain.AuditOutcome;
import com.bank.txn.messaging.event.TransactionEvent;
import com.bank.txn.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Downstream processing of settled transactions.
 *
 * <p>The outbox relay guarantees at-least-once delivery, so this handler is
 * written to tolerate duplicates: the event id is checked against the audit
 * trail before any work happens, which turns redelivery into a no-op rather
 * than a double posting.
 *
 * <p>Anything that keeps throwing ends up on the dead-letter topic (see
 * {@link com.bank.txn.config.KafkaErrorHandlingConfig}) instead of blocking the
 * partition forever.
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);
    private static final String ACTION = "TRANSFER_EVENT_CONSUMED";
    private static final String ENTITY_TYPE = "TransactionEvent";

    private final ObjectMapper objectMapper;
    private final AuditLogRepository auditLogs;
    private final MeterRegistry meterRegistry;

    public TransactionEventConsumer(ObjectMapper objectMapper,
                                    AuditLogRepository auditLogs,
                                    MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.auditLogs = auditLogs;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${banking.kafka.topics.transactions}",
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onTransaction(@Payload String payload,
                              @Header(name = "kafka_receivedPartitionId", required = false) Integer partition) {
        TransactionEvent event = read(payload);

        if (auditLogs.existsByEntityTypeAndEntityIdAndAction(
                ENTITY_TYPE, event.eventId().toString(), ACTION)) {
            log.debug("Skipping already-processed event {}", event.eventId());
            return;
        }

        auditLogs.save(new AuditLog(
                event.initiatedBy(),
                ACTION,
                ENTITY_TYPE,
                event.eventId().toString(),
                TransactionEvent.TRANSFER_COMPLETED.equals(event.eventType())
                        ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                details(event),
                null));

        meterRegistry.counter("banking.events.consumed",
                "type", event.eventType(),
                "currency", event.currency()).increment();

        log.info("Processed {} for transfer {} ({} {}) from partition {}",
                event.eventType(), event.reference(), event.amount(), event.currency(), partition);
    }

    private TransactionEvent read(String payload) {
        try {
            return objectMapper.readValue(payload, TransactionEvent.class);
        } catch (Exception e) {
            // Unparseable: retrying will never help, so let the error handler
            // route it straight to the dead-letter topic.
            throw new IllegalArgumentException("Malformed transaction event", e);
        }
    }

    private String details(TransactionEvent event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "reference", event.reference(),
                    "amount", event.amount().toPlainString(),
                    "currency", event.currency(),
                    "status", event.status()));
        } catch (Exception e) {
            return null;
        }
    }
}
