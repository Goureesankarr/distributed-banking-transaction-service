package com.bank.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boots the application against real Postgres, Redis and Kafka containers.
 *
 * <p>The containers are static and started once for the whole JVM rather than
 * per class. These tests exist to exercise the interaction between the service
 * and its infrastructure, and paying the startup cost per test class would
 * make them too slow to run on every push.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("banking")
                    .withUsername("banking")
                    .withPassword("banking")
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected CacheManager cacheManager;

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE audit_log, outbox_event, idempotency_record,
                         ledger_entry, transfer, account, app_user RESTART IDENTITY CASCADE
                """);
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        Set<String> buckets = redis.keys("ratelimit:*");
        if (buckets != null && !buckets.isEmpty()) {
            redis.delete(buckets);
        }
    }

    /** Registers a fresh customer and returns their {@code Authorization} header value. */
    protected String registerAndGetToken(String username) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.of(
                "username", username,
                "password", "correct-horse-battery",
                "fullName", "Test " + username)));

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();

        return "Bearer " + objectMapper.readTree(response).get("accessToken").asText();
    }

    protected String openAccount(String authorization, String currency, String openingBalance) throws Exception {
        String body = """
                {"currency":"%s","openingBalance":%s}""".formatted(currency, openingBalance);

        String response = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("accountNumber").asText();
    }

    protected JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }
}
