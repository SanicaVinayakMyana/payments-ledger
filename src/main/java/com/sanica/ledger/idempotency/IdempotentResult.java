package com.sanica.ledger.idempotency;

/** {@code replayed} is true when the response was served from an earlier execution of the same key. */
public record IdempotentResult(StoredResponse response, boolean replayed) {
}
