package com.sanica.ledger.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One journal line. Positive amounts credit the account, negative amounts debit it. */
public record Entry(
        UUID id,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        OffsetDateTime createdAt) {
}
