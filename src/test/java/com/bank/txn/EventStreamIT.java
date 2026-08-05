package com.bank.txn;

import com.bank.txn.repository.OutboxEventRepository;
import com.bank.txn.service.TransferService;
import com.bank.txn.web.dto.TransferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check of the transactional outbox: a committed transfer must
 * reach Kafka and be processed by the consumer without any dual-write between
 * Postgres and the broker.
 */
@Tag("integration")
class EventStreamIT extends AbstractIntegrationTest {

    @Autowired private TransferService transferService;
    @Autowired private OutboxEventRepository outbox;

    @Test
    @DisplayName("a settled transfer is relayed to Kafka and consumed exactly once")
    void transferEventFlowsThroughKafka() throws Exception {
        String token = registerAndGetToken("streamer");
        String source = openAccount(token, "USD", "100.0000");
        String target = openAccount(token, "USD", "0.0000");

        var outcome = transferService.transfer("streamer", UUID.randomUUID().toString(), false,
                new TransferRequest(source, target, new BigDecimal("42.0000"), "USD", "event test"));
        String reference = objectMapper.readTree(outcome.body()).get("reference").asText();

        assertThat(outbox.findAll())
                .as("the event was written in the same transaction as the money movement")
                .hasSize(1);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(outbox.countPending())
                        .as("the relay drains the outbox")
                        .isZero());

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    // Scoped to this transfer's reference so events left over
                    // from another test class cannot inflate the count.
                    Integer consumed = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM audit_log
                            WHERE action = 'TRANSFER_EVENT_CONSUMED'
                              AND details ->> 'reference' = ?
                            """, Integer.class, reference);
                    assertThat(consumed).as("consumed exactly once").isEqualTo(1);
                });
    }
}
