package com.bank.txn;

import com.bank.txn.repository.AccountRepository;
import com.bank.txn.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired private TransferRepository transfers;
    @Autowired private AccountRepository accounts;

    private String body(String source, String target, String amount) {
        return """
                {"sourceAccountNumber":"%s","targetAccountNumber":"%s","amount":%s,"currency":"USD","description":"rent"}"""
                .formatted(source, target, amount);
    }

    @Test
    @DisplayName("retrying with the same key replays the response and moves money once")
    void retryWithSameKeyIsANoOp() throws Exception {
        String token = registerAndGetToken("ida");
        String source = openAccount(token, "USD", "500.0000");
        String target = openAccount(token, "USD", "0.0000");
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source, target, "125.0000")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source, target, "125.0000")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .as("a replay returns the original response verbatim")
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(transfers.findAll()).hasSize(1);
        assertThat(accounts.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo("375.0000");
        assertThat(accounts.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo("125.0000");
    }

    @Test
    @DisplayName("reusing a key with a different body is refused rather than replayed")
    void keyReuseWithDifferentPayloadIsRejected() throws Exception {
        String token = registerAndGetToken("idb");
        String source = openAccount(token, "USD", "500.0000");
        String target = openAccount(token, "USD", "0.0000");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source, target, "10.0000")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source, target, "999.0000")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(transfers.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a rejected transfer is bound to its key too, so retries stay rejected")
    void businessFailureIsAlsoIdempotent() throws Exception {
        String token = registerAndGetToken("idc");
        String source = openAccount(token, "USD", "5.0000");
        String target = openAccount(token, "USD", "0.0000");
        String key = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/transfers")
                            .header("Authorization", token)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(source, target, "50.0000")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        }

        assertThat(accounts.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo("5.0000");
    }

    @Test
    void transfersWithoutAnIdempotencyKeyAreRefused() throws Exception {
        String token = registerAndGetToken("idd");
        String source = openAccount(token, "USD", "10.0000");
        String target = openAccount(token, "USD", "0.0000");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source, target, "1.0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
