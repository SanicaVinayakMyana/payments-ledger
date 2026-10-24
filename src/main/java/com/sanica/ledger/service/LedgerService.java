package com.sanica.ledger.service;

import static com.sanica.ledger.jooq.Tables.ACCOUNTS;
import static com.sanica.ledger.jooq.Tables.LEDGER_ENTRIES;
import static com.sanica.ledger.jooq.Tables.LEDGER_TRANSACTIONS;

import com.sanica.ledger.domain.Account;
import com.sanica.ledger.domain.AccountKind;
import com.sanica.ledger.domain.Currencies;
import com.sanica.ledger.domain.Entry;
import com.sanica.ledger.domain.InsufficientFundsException;
import com.sanica.ledger.domain.LedgerTransaction;
import com.sanica.ledger.domain.NotFoundException;
import com.sanica.ledger.domain.ReconciliationReport;
import com.sanica.ledger.domain.TransactionType;
import com.sanica.ledger.domain.ValidationException;
import com.sanica.ledger.fx.FxRateProvider;
import com.sanica.ledger.jooq.tables.records.AccountsRecord;
import com.sanica.ledger.jooq.tables.records.LedgerEntriesRecord;
import com.sanica.ledger.jooq.tables.records.LedgerTransactionsRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Double-entry ledger. Every money movement writes one transaction plus entries whose amounts sum to
 * zero per currency, and updates the materialised balances of the user accounts involved, all in the
 * caller's database transaction.
 */
public final class LedgerService {

    private static final List<AccountKind> SYSTEM_KINDS = List.of(AccountKind.EXTERNAL, AccountKind.FX_POOL);

    private final DSLContext dsl;
    private final FxRateProvider fx;
    private final Clock clock;

    public LedgerService(DSLContext dsl, FxRateProvider fx, Clock clock) {
        this.dsl = dsl;
        this.fx = fx;
        this.clock = clock;
    }

    /** System accounts get deterministic ids, so every instance agrees on them without coordination. */
    public static UUID systemAccountId(AccountKind kind, String currency) {
        return UUID.nameUUIDFromBytes((kind.name() + ":" + currency).getBytes(StandardCharsets.UTF_8));
    }

    public void ensureSystemAccounts() {
        for (String currency : Currencies.SUPPORTED) {
            for (AccountKind kind : SYSTEM_KINDS) {
                dsl.insertInto(ACCOUNTS)
                        .set(ACCOUNTS.ID, systemAccountId(kind, currency))
                        .set(ACCOUNTS.OWNER_ID, "system")
                        .set(ACCOUNTS.CURRENCY, currency)
                        .set(ACCOUNTS.KIND, kind.name())
                        .set(ACCOUNTS.BALANCE, BigDecimal.ZERO)
                        .set(ACCOUNTS.CREATED_AT, now())
                        .onConflictDoNothing()
                        .execute();
            }
        }
    }

    public Account openAccount(String ownerId, String currency) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 64) {
            throw new ValidationException("ownerId must be 1-64 characters");
        }
        Currencies.requireSupported(currency);
        AccountsRecord account = dsl.insertInto(ACCOUNTS)
                .set(ACCOUNTS.ID, UUID.randomUUID())
                .set(ACCOUNTS.OWNER_ID, ownerId)
                .set(ACCOUNTS.CURRENCY, currency)
                .set(ACCOUNTS.KIND, AccountKind.USER.name())
                .set(ACCOUNTS.BALANCE, BigDecimal.ZERO)
                .set(ACCOUNTS.CREATED_AT, now())
                .returning()
                .fetchOne();
        return toAccount(account, account.getBalance());
    }

    public Account getAccount(UUID id) {
        AccountsRecord account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).fetchOne();
        if (account == null) {
            throw new NotFoundException("Account " + id + " not found");
        }
        BigDecimal balance = AccountKind.USER.name().equals(account.getKind())
                ? account.getBalance()
                : journalBalance(id);
        return toAccount(account, balance);
    }

    /** Most recent entries first (served by the (account_id, seq DESC) index). */
    public List<Entry> entries(UUID accountId, int limit) {
        getAccount(accountId);
        return dsl.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.ACCOUNT_ID.eq(accountId))
                .orderBy(LEDGER_ENTRIES.SEQ.desc())
                .limit(limit)
                .fetch(LedgerService::toEntry);
    }

    public LedgerTransaction getTransaction(UUID id) {
        LedgerTransactionsRecord transaction = dsl.selectFrom(LEDGER_TRANSACTIONS)
                .where(LEDGER_TRANSACTIONS.ID.eq(id))
                .fetchOne();
        if (transaction == null) {
            throw new NotFoundException("Transaction " + id + " not found");
        }
        List<Entry> entries = dsl.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.TRANSACTION_ID.eq(id))
                .orderBy(LEDGER_ENTRIES.LINE_NO)
                .fetch(LedgerService::toEntry);
        return new LedgerTransaction(
                transaction.getId(),
                TransactionType.valueOf(transaction.getType()),
                transaction.getSourceAccountId(),
                transaction.getDestinationAccountId(),
                transaction.getSourceAmount(),
                transaction.getSourceCurrency(),
                transaction.getDestinationAmount(),
                transaction.getDestinationCurrency(),
                transaction.getFxRate(),
                utc(transaction.getCreatedAt()),
                entries);
    }

    public BigDecimal fxRate(String from, String to) {
        return fx.rate(from, to);
    }

    /** Money entering the ledger: debit the currency's EXTERNAL account, credit the user. */
    public LedgerTransaction deposit(DSLContext tx, UUID accountId, BigDecimal rawAmount) {
        requireId(accountId, "accountId");
        AccountsRecord account = lockUserAccounts(tx, List.of(accountId)).get(accountId);
        String currency = account.getCurrency();
        BigDecimal amount = Currencies.requirePositiveAmount(rawAmount, currency);
        UUID external = systemAccountId(AccountKind.EXTERNAL, currency);

        UUID transactionId = UUID.randomUUID();
        OffsetDateTime now = now();
        List<Entry> entries = List.of(
                new Entry(UUID.randomUUID(), transactionId, external, amount.negate(), currency, now),
                new Entry(UUID.randomUUID(), transactionId, accountId, amount, currency, now));

        LedgerTransaction transaction = new LedgerTransaction(transactionId, TransactionType.DEPOSIT,
                external, accountId, amount, currency, amount, currency, fx.rate(currency, currency), now, entries);
        write(tx, transaction);
        adjustBalance(tx, accountId, amount);
        return transaction;
    }

    /**
     * Moves {@code rawAmount} (in the source account's currency) to another user account. Cross-currency
     * transfers route through the FX pool of each currency so that both currencies stay balanced.
     */
    public LedgerTransaction transfer(DSLContext tx, UUID fromId, UUID toId, BigDecimal rawAmount) {
        requireId(fromId, "fromAccountId");
        requireId(toId, "toAccountId");
        if (fromId.equals(toId)) {
            throw new ValidationException("Source and destination accounts must differ");
        }

        Map<UUID, AccountsRecord> accounts = lockUserAccounts(tx, List.of(fromId, toId));
        AccountsRecord from = accounts.get(fromId);
        AccountsRecord to = accounts.get(toId);
        String sourceCurrency = from.getCurrency();
        String destinationCurrency = to.getCurrency();

        BigDecimal amount = Currencies.requirePositiveAmount(rawAmount, sourceCurrency);
        // Safe to compare: the row is locked, so no concurrent transfer can change this balance until we commit.
        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromId);
        }

        BigDecimal rate = fx.rate(sourceCurrency, destinationCurrency);
        boolean crossCurrency = !sourceCurrency.equals(destinationCurrency);
        BigDecimal converted = crossCurrency
                ? amount.multiply(rate).setScale(Currencies.scale(destinationCurrency), RoundingMode.HALF_EVEN)
                : amount;
        if (converted.signum() <= 0) {
            throw new ValidationException(
                    "Amount is too small to convert from " + sourceCurrency + " to " + destinationCurrency);
        }

        UUID transactionId = UUID.randomUUID();
        OffsetDateTime now = now();
        List<Entry> entries = new ArrayList<>(4);
        entries.add(new Entry(UUID.randomUUID(), transactionId, fromId, amount.negate(), sourceCurrency, now));
        if (crossCurrency) {
            entries.add(new Entry(UUID.randomUUID(), transactionId,
                    systemAccountId(AccountKind.FX_POOL, sourceCurrency), amount, sourceCurrency, now));
            entries.add(new Entry(UUID.randomUUID(), transactionId,
                    systemAccountId(AccountKind.FX_POOL, destinationCurrency), converted.negate(), destinationCurrency, now));
        }
        entries.add(new Entry(UUID.randomUUID(), transactionId, toId, converted, destinationCurrency, now));

        LedgerTransaction transaction = new LedgerTransaction(transactionId, TransactionType.TRANSFER,
                fromId, toId, amount, sourceCurrency, converted, destinationCurrency, rate, now, List.copyOf(entries));
        write(tx, transaction);
        adjustBalance(tx, fromId, amount.negate());
        adjustBalance(tx, toId, converted);
        return transaction;
    }

    /** Checks that every currency nets to zero and every user balance matches its journal. */
    public ReconciliationReport reconcile() {
        Field<BigDecimal> net = DSL.coalesce(DSL.sum(LEDGER_ENTRIES.AMOUNT), BigDecimal.ZERO);
        Map<String, BigDecimal> netByCurrency = new TreeMap<>(dsl
                .select(LEDGER_ENTRIES.CURRENCY, net)
                .from(LEDGER_ENTRIES)
                .groupBy(LEDGER_ENTRIES.CURRENCY)
                .fetchMap(LEDGER_ENTRIES.CURRENCY, net));

        List<UUID> mismatched = dsl
                .select(ACCOUNTS.ID)
                .from(ACCOUNTS)
                .leftJoin(LEDGER_ENTRIES).on(LEDGER_ENTRIES.ACCOUNT_ID.eq(ACCOUNTS.ID))
                .where(ACCOUNTS.KIND.eq(AccountKind.USER.name()))
                .groupBy(ACCOUNTS.ID, ACCOUNTS.BALANCE)
                .having(ACCOUNTS.BALANCE.ne(net))
                .fetch(ACCOUNTS.ID);
        return new ReconciliationReport(netByCurrency, mismatched);
    }

    /**
     * Locks user accounts with SELECT ... FOR NO KEY UPDATE, ordered by id. A single global lock order
     * means two transfers touching the same pair of accounts can never deadlock. NO KEY UPDATE (rather
     * than FOR UPDATE) still lets other transactions insert entries that reference these rows.
     */
    private Map<UUID, AccountsRecord> lockUserAccounts(DSLContext tx, List<UUID> ids) {
        Map<UUID, AccountsRecord> locked = tx.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.in(ids))
                .orderBy(ACCOUNTS.ID)
                .forNoKeyUpdate()
                .fetchMap(ACCOUNTS.ID);
        for (UUID id : ids) {
            AccountsRecord account = locked.get(id);
            if (account == null) {
                throw new NotFoundException("Account " + id + " not found");
            }
            if (!AccountKind.USER.name().equals(account.getKind())) {
                throw new ValidationException("Account " + id + " is a system account");
            }
        }
        return locked;
    }

    private static void write(DSLContext tx, LedgerTransaction transaction) {
        assertBalanced(transaction.entries());
        tx.insertInto(LEDGER_TRANSACTIONS)
                .set(LEDGER_TRANSACTIONS.ID, transaction.id())
                .set(LEDGER_TRANSACTIONS.TYPE, transaction.type().name())
                .set(LEDGER_TRANSACTIONS.SOURCE_ACCOUNT_ID, transaction.sourceAccountId())
                .set(LEDGER_TRANSACTIONS.DESTINATION_ACCOUNT_ID, transaction.destinationAccountId())
                .set(LEDGER_TRANSACTIONS.SOURCE_AMOUNT, transaction.sourceAmount())
                .set(LEDGER_TRANSACTIONS.SOURCE_CURRENCY, transaction.sourceCurrency())
                .set(LEDGER_TRANSACTIONS.DESTINATION_AMOUNT, transaction.destinationAmount())
                .set(LEDGER_TRANSACTIONS.DESTINATION_CURRENCY, transaction.destinationCurrency())
                .set(LEDGER_TRANSACTIONS.FX_RATE, transaction.fxRate())
                .set(LEDGER_TRANSACTIONS.CREATED_AT, transaction.createdAt())
                .execute();

        // One multi-row INSERT instead of a round trip per entry.
        var insert = tx.insertInto(LEDGER_ENTRIES,
                LEDGER_ENTRIES.ID, LEDGER_ENTRIES.TRANSACTION_ID, LEDGER_ENTRIES.LINE_NO, LEDGER_ENTRIES.ACCOUNT_ID,
                LEDGER_ENTRIES.AMOUNT, LEDGER_ENTRIES.CURRENCY, LEDGER_ENTRIES.CREATED_AT);
        int line = 0;
        for (Entry entry : transaction.entries()) {
            insert = insert.values(entry.id(), entry.transactionId(), ++line, entry.accountId(),
                    entry.amount(), entry.currency(), entry.createdAt());
        }
        insert.execute();
    }

    private static void assertBalanced(List<Entry> entries) {
        Map<String, BigDecimal> net = entries.stream().collect(Collectors.groupingBy(
                Entry::currency, Collectors.reducing(BigDecimal.ZERO, Entry::amount, BigDecimal::add)));
        net.forEach((currency, sum) -> {
            if (sum.signum() != 0) {
                throw new IllegalStateException("Unbalanced " + currency + " entries: net " + sum);
            }
        });
    }

    private static void adjustBalance(DSLContext tx, UUID accountId, BigDecimal delta) {
        tx.update(ACCOUNTS)
                .set(ACCOUNTS.BALANCE, ACCOUNTS.BALANCE.plus(delta))
                .where(ACCOUNTS.ID.eq(accountId))
                .execute();
    }

    private BigDecimal journalBalance(UUID accountId) {
        return dsl.select(DSL.coalesce(DSL.sum(LEDGER_ENTRIES.AMOUNT), BigDecimal.ZERO))
                .from(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.ACCOUNT_ID.eq(accountId))
                .fetchOne(0, BigDecimal.class);
    }

    private static void requireId(UUID id, String name) {
        if (id == null) {
            throw new ValidationException(name + " is required");
        }
    }

    private OffsetDateTime now() {
        // PostgreSQL stores microseconds; truncating keeps responses identical to what is read back later.
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** The JDBC driver returns timestamps in the JVM's zone; the API always speaks UTC. */
    private static OffsetDateTime utc(OffsetDateTime timestamp) {
        return timestamp.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static Account toAccount(AccountsRecord record, BigDecimal balance) {
        return new Account(record.getId(), record.getOwnerId(), record.getCurrency(),
                AccountKind.valueOf(record.getKind()), balance, utc(record.getCreatedAt()));
    }

    private static Entry toEntry(LedgerEntriesRecord record) {
        return new Entry(record.getId(), record.getTransactionId(), record.getAccountId(),
                record.getAmount(), record.getCurrency(), utc(record.getCreatedAt()));
    }
}
