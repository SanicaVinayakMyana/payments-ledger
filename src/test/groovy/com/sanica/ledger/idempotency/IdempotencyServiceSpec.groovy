package com.sanica.ledger.idempotency

import com.sanica.ledger.domain.IdempotencyConflictException
import com.sanica.ledger.domain.InsufficientFundsException
import com.sanica.ledger.support.InMemoryResponseCache
import com.sanica.ledger.support.LedgerSpecification
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.function.Function

class IdempotencyServiceSpec extends LedgerSpecification {

    InMemoryResponseCache cache = new InMemoryResponseCache()
    IdempotencyService idempotency = new IdempotencyService(database.dsl(), cache)

    def "replaying a key returns the original response and moves money only once"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)
        def action = transferAction(alice.id(), bob.id(), 10.00, 'hash-1')

        when:
        def first = idempotency.execute('key-1', 'hash-1', action)
        def second = idempotency.execute('key-1', 'hash-1', action)

        then:
        !first.replayed()
        second.replayed()
        second.response() == first.response()
        balance(alice.id()) == 90.00
        transactionCount('TRANSFER') == 1
    }

    def "replays are served from PostgreSQL when the cache is empty"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)
        def action = transferAction(alice.id(), bob.id(), 10.00, 'hash-1')
        def first = idempotency.execute('key-1', 'hash-1', action)
        cache.entries.clear()

        when:
        def replay = idempotency.execute('key-1', 'hash-1', action)

        then:
        replay.replayed()
        replay.response() == first.response()
        transactionCount('TRANSFER') == 1
    }

    def "the cache is populated only after the transaction commits"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)

        when:
        idempotency.execute('key-1', 'hash-1', transferAction(alice.id(), bob.id(), 10.00, 'hash-1'))

        then:
        cache.get('key-1').get().status() == 201
    }

    def "reusing a key with a different payload is rejected"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)
        idempotency.execute('key-1', 'hash-1', transferAction(alice.id(), bob.id(), 10.00, 'hash-1'))

        when:
        idempotency.execute('key-1', 'hash-2', transferAction(alice.id(), bob.id(), 99.00, 'hash-2'))

        then:
        thrown(IdempotencyConflictException)
        balance(alice.id()) == 90.00
    }

    def "a failed request does not consume its key"() {
        given:
        def alice = fundedAccount('EUR', 5.00)
        def bob = fundedAccount('EUR', 0)
        def action = transferAction(alice.id(), bob.id(), 10.00, 'hash-1')

        when: 'the first attempt fails'
        idempotency.execute('key-1', 'hash-1', action)

        then:
        thrown(InsufficientFundsException)

        when: 'the client tops up and retries with the same key'
        deposit(alice.id(), 5.00)
        def retry = idempotency.execute('key-1', 'hash-1', action)

        then:
        !retry.replayed()
        balance(bob.id()) == 10.00
    }

    def "50 clients racing with the same key move money exactly once"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)
        def racing = new IdempotencyService(database.dsl(), new NoopResponseCache())
        def action = transferAction(alice.id(), bob.id(), 10.00, 'hash-1')
        def pool = Executors.newFixedThreadPool(50)
        def startGun = new CountDownLatch(1)

        when:
        def futures = (1..50).collect {
            pool.submit({
                startGun.await()
                racing.execute('race', 'hash-1', action)
            } as Callable<IdempotentResult>)
        }
        startGun.countDown()
        def results = futures*.get()
        pool.shutdown()

        then:
        results.count { !it.replayed() } == 1
        results*.response().unique().size() == 1
        balance(alice.id()) == 90.00
        balance(bob.id()) == 10.00
        transactionCount('TRANSFER') == 1
    }

    private Function<org.jooq.DSLContext, StoredResponse> transferAction(UUID from, UUID to, BigDecimal amount,
                                                                         String hash) {
        { tx ->
            def transaction = ledger.transfer(tx, from, to, amount)
            new StoredResponse(hash, 201, transaction.id().toString())
        } as Function
    }
}
