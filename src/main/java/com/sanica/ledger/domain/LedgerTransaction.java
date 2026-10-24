package com.sanica.ledger.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LedgerTransaction(
        UUID id,
        TransactionType type,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal sourceAmount,
        String sourceCurrency,
        BigDecimal destinationAmount,
        String destinationCurrency,
        BigDecimal fxRate,
        OffsetDateTime createdAt,
        List<Entry> entries) {
}
