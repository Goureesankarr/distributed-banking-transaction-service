package com.bank.txn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
class SecurityIT extends AbstractIntegrationTest {

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aForgedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("one customer cannot read another customer's account")
    void accountsAreIsolatedBetweenCustomers() throws Exception {
        String owner = registerAndGetToken("owner-" + UUID.randomUUID());
        String accountNumber = openAccount(owner, "USD", "10.0000");

        String stranger = registerAndGetToken("stranger-" + UUID.randomUUID());

        mockMvc.perform(get("/api/v1/accounts/" + accountNumber)
                        .header("Authorization", stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FORBIDDEN"));
    }

    @Test
    @DisplayName("a customer cannot spend from an account they do not own")
    void transfersFromSomeoneElsesAccountAreRefused() throws Exception {
        String owner = registerAndGetToken("victim-" + UUID.randomUUID());
        String victimAccount = openAccount(owner, "USD", "1000.0000");

        String attacker = registerAndGetToken("attacker-" + UUID.randomUUID());
        String attackerAccount = openAccount(attacker, "USD", "0.0000");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", attacker)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountNumber":"%s","targetAccountNumber":"%s","amount":500.0000,"currency":"USD"}"""
                                .formatted(victimAccount, attackerAccount)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FORBIDDEN"));
    }

    @Test
    void adminEndpointsRequireTheAdminRole() throws Exception {
        String customer = registerAndGetToken("plain-" + UUID.randomUUID());

        mockMvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", customer))
                .andExpect(status().isForbidden());
    }
}
