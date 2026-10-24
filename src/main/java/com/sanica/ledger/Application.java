package com.sanica.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.ledger.api.Json;
import com.sanica.ledger.api.LedgerApi;
import com.sanica.ledger.db.Database;
import com.sanica.ledger.fx.StaticFxRateProvider;
import com.sanica.ledger.idempotency.IdempotencyService;
import com.sanica.ledger.idempotency.NoopResponseCache;
import com.sanica.ledger.idempotency.RedisResponseCache;
import com.sanica.ledger.idempotency.ResponseCache;
import com.sanica.ledger.service.LedgerService;
import io.javalin.Javalin;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires the components together and owns their lifecycle. */
public final class Application implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Application.class);
    private static final Duration IDEMPOTENCY_CACHE_TTL = Duration.ofHours(24);

    private final Javalin server;
    private final AutoCloseable[] ownedResources;

    private Application(Javalin server, AutoCloseable... ownedResources) {
        this.server = server;
        this.ownedResources = ownedResources;
    }

    public static void main(String[] args) {
        Application app = start(Config.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    public static Application start(Config config) {
        ObjectMapper mapper = Json.mapper();
        Database database = Database.connect(config.jdbcUrl(), config.dbUser(), config.dbPassword(), config.dbPoolSize());
        ResponseCache cache = config.redisUrl() == null
                ? new NoopResponseCache()
                : new RedisResponseCache(config.redisUrl(), mapper, IDEMPOTENCY_CACHE_TTL);
        log.info("Idempotency cache: {}", config.redisUrl() == null ? "disabled (PostgreSQL only)" : "Redis");
        return new Application(server(config.port(), database, cache, mapper), cache, database);
    }

    /** Starts the API on resources owned by the caller (used by tests and the local dev server). */
    public static Application start(int port, Database database, ResponseCache cache) {
        return new Application(server(port, database, cache, Json.mapper()));
    }

    private static Javalin server(int port, Database database, ResponseCache cache, ObjectMapper mapper) {
        LedgerService ledger = new LedgerService(database.dsl(), new StaticFxRateProvider(), Clock.systemUTC());
        ledger.ensureSystemAccounts();
        IdempotencyService idempotency = new IdempotencyService(database.dsl(), cache);
        return LedgerApi.create(ledger, idempotency, mapper).start(port);
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.stop();
        for (AutoCloseable resource : ownedResources) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Failed to close {}", resource.getClass().getSimpleName(), e);
            }
        }
    }
}
