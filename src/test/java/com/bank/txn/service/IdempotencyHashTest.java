package com.bank.txn.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHashTest {

    @Test
    void hashIsStableForTheSameInput() {
        assertThat(IdempotencyService.hash("ACC1|ACC2|10|USD|rent"))
                .isEqualTo(IdempotencyService.hash("ACC1|ACC2|10|USD|rent"))
                .hasSize(64);
    }

    @Test
    void hashChangesWhenAnyFieldChanges() {
        String base = IdempotencyService.hash("ACC1|ACC2|10|USD|rent");

        assertThat(IdempotencyService.hash("ACC1|ACC2|11|USD|rent")).isNotEqualTo(base);
        assertThat(IdempotencyService.hash("ACC1|ACC3|10|USD|rent")).isNotEqualTo(base);
        assertThat(IdempotencyService.hash("ACC1|ACC2|10|EUR|rent")).isNotEqualTo(base);
    }
}
