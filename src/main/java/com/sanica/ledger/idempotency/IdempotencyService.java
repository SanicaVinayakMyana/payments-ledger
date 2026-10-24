package com.sanica.ledger.idempotency;

import static com.sanica.ledger.jooq.Tables.IDEMPOTENCY_KEYS;

import com.sanica.ledger.domain.IdempotencyConflictException;
import com.sanica.ledger.jooq.tables.records.IdempotencyKeysRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Function;
import org.jooq.DSLContext;

/**
 * Exactly-once execution of POST requests keyed by the client's Idempotency-Key.
 *
 * <p>The key row is inserted in the same database transaction as the money movement, so the two commit
 * or roll back together. A concurrent request with the same key blocks on the uncommitted row's unique
 * index entry; once the first request commits, the second sees the conflict and replays the stored
 * response instead of moving money again.
 */
public final class IdempotencyService {

    private final DSLContext dsl;
    private final ResponseCache cache;

    public IdempotencyService(DSLContext dsl, ResponseCache cache) {
        this.dsl = dsl;
        this.cache = cache;
    }

    public IdempotentResult execute(String key, String requestHash, Function<DSLContext, StoredResponse> action) {
        Optional<StoredResponse> cached = cache.get(key);
        if (cached.isPresent()) {
            return replay(key, cached.get(), requestHash);
        }

        IdempotentResult result = dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            int inserted = tx.insertInto(IDEMPOTENCY_KEYS)
                    .set(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
                    .set(IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
                    .set(IDEMPOTENCY_KEYS.STATUS_CODE, 0)
                    .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, "")
                    .set(IDEMPOTENCY_KEYS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute();
            if (inserted == 0) {
                IdempotencyKeysRecord existing = tx.selectFrom(IDEMPOTENCY_KEYS)
                        .where(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
                        .fetchSingle();
                return replay(key, toStoredResponse(existing), requestHash);
            }

            StoredResponse response = action.apply(tx);
            tx.update(IDEMPOTENCY_KEYS)
                    .set(IDEMPOTENCY_KEYS.STATUS_CODE, response.status())
                    .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, response.body())
                    .where(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
                    .execute();
            return new IdempotentResult(response, false);
        });

        // Populated only after commit, so the cache can never hold a response that was rolled back.
        cache.put(key, result.response());
        return result;
    }

    private static IdempotentResult replay(String key, StoredResponse stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);
        }
        return new IdempotentResult(stored, true);
    }

    private static StoredResponse toStoredResponse(IdempotencyKeysRecord record) {
        return new StoredResponse(record.getRequestHash(), record.getStatusCode(), record.getResponseBody());
    }
}
