package com.bank.txn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@TestPropertySource(properties = {
        "banking.rate-limit.transfer-capacity=3",
        "banking.rate-limit.transfer-refill-per-minute=3",
        "banking.rate-limit.default-capacity=50",
        "banking.rate-limit.default-refill-per-minute=50"
})
class RateLimitIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("money movement is throttled once the bucket empties, reads are not")
    void transferBucketIsEnforcedIndependentlyOfReads() throws Exception {
        String token = registerAndGetToken("burst");
        String source = openAccount(token, "USD", "1000.0000");
        String target = openAccount(token, "USD", "0.0000");

        String body = """
                {"sourceAccountNumber":"%s","targetAccountNumber":"%s","amount":1.0000,"currency":"USD"}"""
                .formatted(source, target);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/transfers")
                            .header("Authorization", token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        MvcResult throttled = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andReturn();

        assertThat(throttled.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(throttled.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("3");

        // The read bucket is separate and still has room.
        mockMvc.perform(get("/api/v1/accounts/" + source).header("Authorization", token))
                .andExpect(status().isOk());
    }
}
