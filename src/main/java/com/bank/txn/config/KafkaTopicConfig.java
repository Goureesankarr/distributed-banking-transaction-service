package com.bank.txn.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transactionsTopic(BankingProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getTransactions())
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditTopic(BankingProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getAudit())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionsDlt(BankingProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getDlt())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
