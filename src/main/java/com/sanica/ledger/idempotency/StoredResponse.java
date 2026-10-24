package com.sanica.ledger.idempotency;

/** The response recorded for an idempotency key, plus a hash of the request that produced it. */
public record StoredResponse(String requestHash, int status, String body) {
}
