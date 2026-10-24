package com.sanica.ledger.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        UUID id,
        String ownerId,
        String currency,
        AccountKind kind,
        BigDecimal balance,
        OffsetDateTime createdAt) {
}
