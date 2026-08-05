package com.bank.txn.messaging;

import com.bank.txn.config.BankingProperties;
import com.bank.txn.domain.OutboxEvent;
import com.bank.txn.messaging.event.TransactionEvent;
import com.bank.txn.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka.
 *
 * <p>This is the second half of the transactional outbox: the transfer and its
 * event commit together in Postgres, then this poller moves the event to the
 * broker. Delivery is at-least-once (a crash between {@code send} and the
 * {@code published_at} update replays the event), which is why consumers are
 * written to be idempotent.
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so running several
 * instances of the service simply shares the work instead of duplicating it.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final BankingProperties properties;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxEventRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           BankingProperties properties,
                           MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
        this.published = Counter.builder("banking.outbox.published")
                .description("Outbox events successfully relayed to Kafka")
                .register(meterRegistry);
        this.failed = Counter.builder("banking.outbox.send_failures")
                .description("Outbox relay attempts that failed")
                .register(meterRegistry);
        Gauge.builder("banking.outbox.pending", outbox, OutboxEventRepository::countPending)
                .description("Events written but not yet on the broker")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${banking.outbox.poll-interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outbox.claimUnpublished(
                properties.getOutbox().getMaxAttempts(),
                properties.getOutbox().getBatchSize());
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                kafka.send(topicFor(event), event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markPublished();
                published.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("interrupted");
                break;
            } catch (Exception e) {
                // Left unpublished: the next poll retries it until
                // max-attempts, after which it needs a human.
                event.recordFailure(e.getMessage());
                failed.increment();
                log.warn("Failed to relay outbox event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), e.getMessage());
            }
        }
        outbox.saveAll(batch);
    }

    private String topicFor(OutboxEvent event) {
        BankingProperties.Kafka.Topics topics = properties.getKafka().getTopics();
        return switch (event.getEventType()) {
            case TransactionEvent.TRANSFER_COMPLETED, TransactionEvent.TRANSFER_FAILED -> topics.getTransactions();
            default -> topics.getAudit();
        };
    }
}
