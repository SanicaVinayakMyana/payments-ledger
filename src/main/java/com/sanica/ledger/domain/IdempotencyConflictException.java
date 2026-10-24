package com.sanica.ledger.domain;

public final class IdempotencyConflictException extends LedgerException {

    public IdempotencyConflictException(String key) {
        super("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key '" + key + "' was already used with a different request");
    }
}
