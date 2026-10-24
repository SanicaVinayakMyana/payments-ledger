package com.sanica.ledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Redis-backed response cache. Failures are logged and treated as a miss so that a Redis outage
 * degrades latency, never correctness or availability of payments.
 */
public final class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);
    private static final String PREFIX = "idempotency:";

    private final JedisPooled redis;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    public RedisResponseCache(String redisUrl, ObjectMapper mapper, Duration ttl) {
        this.redis = new JedisPooled(URI.create(redisUrl));
        this.mapper = mapper;
        this.ttlSeconds = ttl.toSeconds();
    }

    @Override
    public Optional<StoredResponse> get(String key) {
        try {
            String json = redis.get(PREFIX + key);
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, StoredResponse.class));
        } catch (JedisException | JsonProcessingException e) {
            log.warn("Idempotency cache read failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, StoredResponse response) {
        try {
            redis.setex(PREFIX + key, ttlSeconds, mapper.writeValueAsString(response));
        } catch (JedisException | JsonProcessingException e) {
            log.warn("Idempotency cache write failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void close() {
        redis.close();
    }
}
