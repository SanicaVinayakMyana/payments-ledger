package com.sanica.ledger.idempotency;

import java.util.Optional;

/**
 * Read-through cache in front of the idempotency table. It is never the source of truth: a miss (or an
 * unavailable cache) simply falls back to PostgreSQL.
 */
public interface ResponseCache extends AutoCloseable {

    Optional<StoredResponse> get(String key);

    void put(String key, StoredResponse response);

    @Override
    default void close() {
    }
}
