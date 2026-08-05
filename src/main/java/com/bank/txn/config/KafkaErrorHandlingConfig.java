package com.bank.txn.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    /**
     * Retries a failing record a few times, then parks it on the dead-letter
     * topic. Without this a single poison message would block its partition
     * indefinitely and stall every transfer event behind it.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                                 BankingProperties properties) {
        String dlt = properties.getKafka().getTopics().getDlt();

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, exception) -> {
            log.error("Routing record from {}-{} offset {} to {}",
                    record.topic(), record.partition(), record.offset(), dlt, exception);
            return new TopicPartition(dlt, 0);
        });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));
        // Nothing about a malformed payload improves on retry.
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
