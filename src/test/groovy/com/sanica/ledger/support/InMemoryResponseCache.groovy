package com.sanica.ledger.support

import com.sanica.ledger.idempotency.ResponseCache
import com.sanica.ledger.idempotency.StoredResponse
import java.util.concurrent.ConcurrentHashMap

class InMemoryResponseCache implements ResponseCache {

    final Map<String, StoredResponse> entries = new ConcurrentHashMap<>()

    @Override
    Optional<StoredResponse> get(String key) {
        Optional.ofNullable(entries[key])
    }

    @Override
    void put(String key, StoredResponse response) {
        entries[key] = response
    }
}
