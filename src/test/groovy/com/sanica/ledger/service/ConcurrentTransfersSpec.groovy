package com.sanica.ledger.service

import static com.sanica.ledger.domain.AccountKind.FX_POOL
import static com.sanica.ledger.jooq.Tables.IDEMPOTENCY_KEYS

import com.sanica.ledger.domain.InsufficientFundsException
import com.sanica.ledger.idempotency.IdempotencyService
import com.sanica.ledger.idempotency.NoopResponseCache
import com.sanica.ledger.idempotency.StoredResponse
import com.sanica.ledger.support.LedgerSpecification
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import spock.lang.Shared

/**
 * The core correctness claim: under heavy concurrency the ledger never creates or destroys money,
 * never overdraws an account, never deadlocks, and its materialised balances always match the journal.
 */
class ConcurrentTransfersSpec extends LedgerSpecification {

    static final int TRANSFERS = 10_000
    static final int THREADS = 32
    static final List<String> CURRENCIES = ['EUR'] * 8 + ['USD'] * 6 + ['GBP'] * 6

    @Shared
    IdempotencyService idempotency = new IdempotencyService(database.dsl(), new NoopResponseCache())

    def "10,000 concurrent transfers never create or destroy money"() {
        given: '20 accounts across EUR, USD and GBP, each funded with 1,000.00'
        def accounts = CURRENCIES.collect { fundedAccount(it, 1000.00) }
        def depositedByCurrency = accounts.groupBy { it.currency() }
                .collectEntries { currency, list -> [currency, list*.balance().sum()] }

        and: 'a reproducible random workload of same- and cross-currency transfers'
        def random = new Random(42)
        def jobs = (1..TRANSFERS).collect {
            def from = accounts[random.nextInt(accounts.size())]
            def others = accounts - from
            def to = others[random.nextInt(others.size())]
            [from: from.id(), to: to.id(), amount: BigDecimal.valueOf(1 + random.nextInt(25_000), 2)]
        }

        and:
        def pool = Executors.newFixedThreadPool(THREADS)
        def startGun = new CountDownLatch(1)
        def completed = new AtomicInteger()
        def rejected = new AtomicInteger()

        when: 'all transfers are fired at once through the full idempotent write path'
        def futures = jobs.collect { job ->
            pool.submit({
                startGun.await()
                try {
                    idempotency.execute(UUID.randomUUID().toString(), 'load-test', { tx ->
                        ledger.transfer(tx, job.from as UUID, job.to as UUID, job.amount as BigDecimal)
                        new StoredResponse('load-test', 201, '{}')
                    } as Function)
                    completed.incrementAndGet()
                } catch (InsufficientFundsException ignored) {
                    rejected.incrementAndGet()
                }
                null
            } as Callable)
        }
        long started = System.nanoTime()
        startGun.countDown()
        futures*.get()   // re-throws any unexpected failure, e.g. a deadlock
        long elapsedMs = (System.nanoTime() - started).intdiv(1_000_000)
        pool.shutdown()
        println "Executed ${TRANSFERS} concurrent transfers in ${elapsedMs} ms on ${THREADS} threads " +
                "(${completed.get()} completed, ${rejected.get()} rejected for insufficient funds)"

        then: 'every transfer either committed or was cleanly rejected'
        completed.get() + rejected.get() == TRANSFERS
        completed.get() > TRANSFERS / 2
        transactionCount('TRANSFER') == completed.get()
        database.dsl().fetchCount(IDEMPOTENCY_KEYS) == completed.get()

        and: 'no account was overdrawn'
        accounts.every { balance(it.id()) >= 0 }

        and: 'per currency, user balances plus the FX pool still equal what was deposited (zero drift)'
        depositedByCurrency.every { currency, deposited ->
            def users = accounts.findAll { it.currency() == currency }.sum { balance(it.id()) }
            users + balance(LedgerService.systemAccountId(FX_POOL, currency)) == deposited
        }

        and: 'the journal nets to zero and matches every materialised balance'
        ledger.reconcile().balanced()
    }
}
