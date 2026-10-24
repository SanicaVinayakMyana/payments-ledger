package com.sanica.ledger.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.ledger.api.Dtos.AccountResponse;
import com.sanica.ledger.api.Dtos.CreateAccountRequest;
import com.sanica.ledger.api.Dtos.DepositRequest;
import com.sanica.ledger.api.Dtos.EntryResponse;
import com.sanica.ledger.api.Dtos.ErrorResponse;
import com.sanica.ledger.api.Dtos.ReconciliationResponse;
import com.sanica.ledger.api.Dtos.TransactionResponse;
import com.sanica.ledger.api.Dtos.TransferRequest;
import com.sanica.ledger.domain.Currencies;
import com.sanica.ledger.domain.IdempotencyConflictException;
import com.sanica.ledger.domain.InsufficientFundsException;
import com.sanica.ledger.domain.LedgerException;
import com.sanica.ledger.domain.LedgerTransaction;
import com.sanica.ledger.domain.NotFoundException;
import com.sanica.ledger.domain.ReconciliationReport;
import com.sanica.ledger.domain.ValidationException;
import com.sanica.ledger.idempotency.IdempotencyService;
import com.sanica.ledger.idempotency.IdempotentResult;
import com.sanica.ledger.idempotency.StoredResponse;
import com.sanica.ledger.service.LedgerService;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP routes. Thin by design: parse, delegate to the service layer, map errors to status codes. */
public final class LedgerApi {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(LedgerApi.class);
    private static final int MAX_KEY_LENGTH = 128;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    private LedgerApi(LedgerService ledger, IdempotencyService idempotency, ObjectMapper mapper) {
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    public static Javalin create(LedgerService ledger, IdempotencyService idempotency, ObjectMapper mapper) {
        LedgerApi api = new LedgerApi(ledger, idempotency, mapper);
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // Virtual threads: blocking JDBC calls no longer pin a platform thread per request.
            config.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson(mapper, true));
        });

        app.get("/health", ctx -> ctx.json(Map.of("status", "UP")));
        app.post("/accounts", api::createAccount);
        app.get("/accounts/{id}", api::getAccount);
        app.get("/accounts/{id}/entries", api::listEntries);
        app.post("/deposits", api::deposit);
        app.post("/transfers", api::transfer);
        app.get("/transactions/{id}", api::getTransaction);
        app.get("/fx/rate", api::fxRate);
        app.get("/admin/reconciliation", api::reconciliation);

        app.exception(LedgerException.class, api::handleLedgerException);
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Unexpected error"));
        });
        return app;
    }

    private void createAccount(Context ctx) {
        CreateAccountRequest request = read(ctx, CreateAccountRequest.class);
        ctx.status(201).json(AccountResponse.from(ledger.openAccount(request.ownerId(), request.currency())));
    }

    private void getAccount(Context ctx) {
        ctx.json(AccountResponse.from(ledger.getAccount(uuidParam(ctx, "id"))));
    }

    private void listEntries(Context ctx) {
        int limit = intQuery(ctx, "limit", DEFAULT_PAGE_SIZE);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ValidationException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        ctx.json(ledger.entries(uuidParam(ctx, "id"), limit).stream().map(EntryResponse::from).toList());
    }

    private void deposit(Context ctx) {
        DepositRequest request = read(ctx, DepositRequest.class);
        idempotent(ctx, request, tx -> ledger.deposit(tx, request.accountId(), request.amount()));
    }

    private void transfer(Context ctx) {
        TransferRequest request = read(ctx, TransferRequest.class);
        idempotent(ctx, request,
                tx -> ledger.transfer(tx, request.fromAccountId(), request.toAccountId(), request.amount()));
    }

    private void getTransaction(Context ctx) {
        ctx.json(TransactionResponse.from(ledger.getTransaction(uuidParam(ctx, "id"))));
    }

    private void fxRate(Context ctx) {
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        ctx.json(Map.of("from", String.valueOf(from), "to", String.valueOf(to),
                "rate", ledger.fxRate(from, to).toPlainString()));
    }

    private void reconciliation(Context ctx) {
        ReconciliationReport report = ledger.reconcile();
        Map<String, String> net = new LinkedHashMap<>();
        report.netByCurrency().forEach((currency, amount) -> net.put(currency, Currencies.format(amount, currency)));
        ctx.json(new ReconciliationResponse(report.balanced(), net, report.mismatchedAccounts()));
    }

    private void idempotent(Context ctx, Object request,
                            Function<DSLContext, LedgerTransaction> action) {
        String key = ctx.header(IDEMPOTENCY_KEY);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ValidationException(IDEMPOTENCY_KEY + " header is required (1-" + MAX_KEY_LENGTH + " characters)");
        }
        String requestHash = hash(ctx.method() + " " + ctx.path() + "\n" + toJson(request));
        IdempotentResult result = idempotency.execute(key, requestHash,
                tx -> new StoredResponse(requestHash, 201, toJson(TransactionResponse.from(action.apply(tx)))));

        ctx.status(result.response().status())
                .contentType(ContentType.APPLICATION_JSON)
                .header(IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
                .result(result.response().body());
    }

    private void handleLedgerException(LedgerException e, Context ctx) {
        int status = switch (e) {
            case NotFoundException ignored -> 404;
            case ValidationException ignored -> 400;
            case InsufficientFundsException ignored -> 422;
            case IdempotencyConflictException ignored -> 422;
        };
        ctx.status(status).json(new ErrorResponse(e.code(), e.getMessage()));
    }

    private <T> T read(Context ctx, Class<T> type) {
        try {
            T value = mapper.readValue(ctx.body(), type);
            if (value == null) {
                throw new ValidationException("Request body is required");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new ValidationException("Malformed request body: " + e.getOriginalMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private static UUID uuidParam(Context ctx, String name) {
        try {
            return UUID.fromString(ctx.pathParam(name));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(name + " must be a UUID");
        }
    }

    private static int intQuery(Context ctx, String name, int defaultValue) {
        String raw = ctx.queryParam(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ValidationException(name + " must be an integer");
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
