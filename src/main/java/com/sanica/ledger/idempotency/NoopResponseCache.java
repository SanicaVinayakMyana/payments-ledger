package com.sanica.ledger.idempotency;

import java.util.Optional;

/** Used when REDIS_URL is not configured: every lookup goes to PostgreSQL. */
public final class NoopResponseCache implements ResponseCache {

    @Override
    public Optional<StoredResponse> get(String key) {
        return Optional.empty();
    }

    @Override
    public void put(String key, StoredResponse response) {
    }
}
