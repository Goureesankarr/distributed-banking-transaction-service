package com.bank.txn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Strongly typed view of the {@code banking.*} configuration tree.
 */
@ConfigurationProperties(prefix = "banking")
public class BankingProperties {

    private final Jwt jwt = new Jwt();
    private final Idempotency idempotency = new Idempotency();
    private final RateLimit rateLimit = new RateLimit();
    private final Outbox outbox = new Outbox();
    private final Kafka kafka = new Kafka();

    public Jwt getJwt() {
        return jwt;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public static class Jwt {
        /** Base64-encoded HMAC key, at least 256 bits. */
        private String secret;
        private String issuer = "distributed-banking-transaction-service";
        private Duration accessTokenTtl = Duration.ofMinutes(30);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }
    }

    public static class Idempotency {
        /** How long a replayable response is retained. */
        private Duration ttl = Duration.ofHours(24);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int defaultCapacity = 100;
        private int defaultRefillPerMinute = 100;
        private int transferCapacity = 20;
        private int transferRefillPerMinute = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultCapacity() {
            return defaultCapacity;
        }

        public void setDefaultCapacity(int defaultCapacity) {
            this.defaultCapacity = defaultCapacity;
        }

        public int getDefaultRefillPerMinute() {
            return defaultRefillPerMinute;
        }

        public void setDefaultRefillPerMinute(int defaultRefillPerMinute) {
            this.defaultRefillPerMinute = defaultRefillPerMinute;
        }

        public int getTransferCapacity() {
            return transferCapacity;
        }

        public void setTransferCapacity(int transferCapacity) {
            this.transferCapacity = transferCapacity;
        }

        public int getTransferRefillPerMinute() {
            return transferRefillPerMinute;
        }

        public void setTransferRefillPerMinute(int transferRefillPerMinute) {
            this.transferRefillPerMinute = transferRefillPerMinute;
        }
    }

    public static class Outbox {
        /** Plain milliseconds so {@code @Scheduled(fixedDelayString)} can read it directly. */
        private long pollIntervalMs = 500;
        private int batchSize = 100;
        private int maxAttempts = 10;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Kafka {
        private final Topics topics = new Topics();

        public Topics getTopics() {
            return topics;
        }

        public static class Topics {
            private String transactions = "banking.transactions.v1";
            private String audit = "banking.audit.v1";
            private String dlt = "banking.transactions.dlt";

            public String getTransactions() {
                return transactions;
            }

            public void setTransactions(String transactions) {
                this.transactions = transactions;
            }

            public String getAudit() {
                return audit;
            }

            public void setAudit(String audit) {
                this.audit = audit;
            }

            public String getDlt() {
                return dlt;
            }

            public void setDlt(String dlt) {
                this.dlt = dlt;
            }
        }
    }
}
