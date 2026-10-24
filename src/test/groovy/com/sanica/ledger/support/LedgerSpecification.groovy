package com.sanica.ledger.support

import static com.sanica.ledger.jooq.Tables.LEDGER_TRANSACTIONS

import com.sanica.ledger.db.Database
import com.sanica.ledger.domain.Account
import com.sanica.ledger.domain.LedgerTransaction
import com.sanica.ledger.fx.StaticFxRateProvider
import com.sanica.ledger.service.LedgerService
import java.time.Clock
import org.jooq.TransactionalCallable
import spock.lang.Shared
import spock.lang.Specification

/** Base for specs that need a real database. Every feature starts from an empty ledger. */
abstract class LedgerSpecification extends Specification {

    @Shared
    Database database = TestDatabase.get()

    @Shared
    LedgerService ledger = new LedgerService(database.dsl(), new StaticFxRateProvider(), Clock.systemUTC())

    def setup() {
        TestDatabase.reset(database)
        ledger.ensureSystemAccounts()
    }

    protected Account fundedAccount(String currency, Number amount) {
        def account = ledger.openAccount("owner-${UUID.randomUUID()}", currency)
        if (amount > 0) {
            deposit(account.id(), amount)
        }
        ledger.getAccount(account.id())
    }

    protected LedgerTransaction deposit(UUID accountId, Number amount) {
        def value = money(amount)
        database.dsl().transactionResult({ cfg -> ledger.deposit(cfg.dsl(), accountId, value) } as TransactionalCallable)
    }

    protected LedgerTransaction transfer(UUID from, UUID to, Number amount) {
        def value = money(amount)
        database.dsl().transactionResult({ cfg -> ledger.transfer(cfg.dsl(), from, to, value) } as TransactionalCallable)
    }

    protected static BigDecimal money(Number amount) {
        new BigDecimal(amount.toString())
    }

    protected BigDecimal balance(UUID accountId) {
        ledger.getAccount(accountId).balance()
    }

    protected int transactionCount(String type) {
        database.dsl().fetchCount(LEDGER_TRANSACTIONS, LEDGER_TRANSACTIONS.TYPE.eq(type))
    }
}
