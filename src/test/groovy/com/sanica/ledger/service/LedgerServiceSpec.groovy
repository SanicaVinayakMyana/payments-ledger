package com.sanica.ledger.service

import static com.sanica.ledger.domain.AccountKind.EXTERNAL
import static com.sanica.ledger.domain.AccountKind.FX_POOL

import com.sanica.ledger.domain.InsufficientFundsException
import com.sanica.ledger.domain.NotFoundException
import com.sanica.ledger.domain.TransactionType
import com.sanica.ledger.domain.ValidationException
import com.sanica.ledger.support.LedgerSpecification

class LedgerServiceSpec extends LedgerSpecification {

    def "opens an empty user account"() {
        when:
        def account = ledger.openAccount('alice', 'EUR')

        then:
        account.balance() == 0
        ledger.getAccount(account.id()).currency() == 'EUR'
    }

    def "rejects accounts with #description"() {
        when:
        ledger.openAccount(owner, currency)

        then:
        thrown(ValidationException)

        where:
        owner      | currency | description
        ''         | 'EUR'    | 'a blank owner'
        'x' * 65   | 'EUR'    | 'an over-long owner'
        'alice'    | 'XYZ'    | 'an unsupported currency'
    }

    def "a deposit credits the user and debits the currency's external account"() {
        given:
        def account = ledger.openAccount('alice', 'EUR')
        def external = LedgerService.systemAccountId(EXTERNAL, 'EUR')

        when:
        def tx = deposit(account.id(), 250.00)

        then:
        tx.type() == TransactionType.DEPOSIT
        tx.entries()*.accountId() == [external, account.id()]
        tx.entries()*.amount() == [-250.00, 250.00]
        balance(account.id()) == 250.00
        balance(external) == -250.00
    }

    def "a same-currency transfer writes two balanced entries"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)

        when:
        def tx = transfer(alice.id(), bob.id(), 40.00)

        then:
        tx.entries()*.amount() == [-40.00, 40.00]
        tx.fxRate() == 1
        balance(alice.id()) == 60.00
        balance(bob.id()) == 40.00
    }

    def "a cross-currency transfer converts through each currency's FX pool"() {
        given:
        def eur = fundedAccount('EUR', 100.00)
        def usd = fundedAccount('USD', 0)

        when:
        def tx = transfer(eur.id(), usd.id(), 100.00)

        then: 'the recipient is credited at the EUR/USD rate'
        tx.fxRate() == 1.08
        tx.destinationAmount() == 108.00
        balance(eur.id()) == 0
        balance(usd.id()) == 108.00

        and: 'each currency balances on its own'
        tx.entries()*.accountId() == [eur.id(), LedgerService.systemAccountId(FX_POOL, 'EUR'),
                                      LedgerService.systemAccountId(FX_POOL, 'USD'), usd.id()]
        tx.entries().groupBy { it.currency() }.every { currency, lines -> lines*.amount().sum() == 0 }

        and: 'the FX pools hold the opposite positions'
        balance(LedgerService.systemAccountId(FX_POOL, 'EUR')) == 100.00
        balance(LedgerService.systemAccountId(FX_POOL, 'USD')) == -108.00
    }

    def "converted amounts are rounded half-even to the destination's minor unit"() {
        given:
        def eur = fundedAccount('EUR', 10.00)
        def jpy = fundedAccount('JPY', 0)

        when: '1.01 EUR x 162 = 163.62 JPY'
        def tx = transfer(eur.id(), jpy.id(), 1.01)

        then:
        tx.destinationAmount() == 164
        balance(jpy.id()) == 164
    }

    def "rejects a transfer too small to be represented in the destination currency"() {
        given: '0.01 INR is worth 0.0179 JPY, which rounds to zero yen'
        def inr = fundedAccount('INR', 1.00)
        def jpy = fundedAccount('JPY', 0)

        when:
        transfer(inr.id(), jpy.id(), 0.01)

        then:
        thrown(ValidationException)
        balance(inr.id()) == 1.00
    }

    def "insufficient funds leave balances and the journal untouched"() {
        given:
        def alice = fundedAccount('EUR', 50.00)
        def bob = fundedAccount('EUR', 0)

        when:
        transfer(alice.id(), bob.id(), 50.01)

        then:
        thrown(InsufficientFundsException)
        balance(alice.id()) == 50.00
        balance(bob.id()) == 0
        transactionCount('TRANSFER') == 0
    }

    def "an account can be drained to exactly zero"() {
        given:
        def alice = fundedAccount('GBP', 12.34)
        def bob = fundedAccount('GBP', 0)

        when:
        transfer(alice.id(), bob.id(), 12.34)

        then:
        balance(alice.id()) == 0
        balance(bob.id()) == 12.34
    }

    def "rejects a transfer to the same account"() {
        given:
        def alice = fundedAccount('EUR', 10.00)

        when:
        transfer(alice.id(), alice.id(), 1.00)

        then:
        thrown(ValidationException)
    }

    def "rejects transfers involving unknown accounts"() {
        given:
        def alice = fundedAccount('EUR', 10.00)

        when:
        transfer(alice.id(), UUID.randomUUID(), 1.00)

        then:
        thrown(NotFoundException)
    }

    def "system accounts cannot be moved directly"() {
        given:
        def alice = fundedAccount('EUR', 0)

        when:
        transfer(LedgerService.systemAccountId(EXTERNAL, 'EUR'), alice.id(), 1_000_000.00)

        then:
        thrown(ValidationException)
        balance(alice.id()) == 0
    }

    def "rejects missing account ids with a validation error"() {
        when:
        transfer(null, UUID.randomUUID(), 1.00)

        then:
        thrown(ValidationException)
    }

    def "the database refuses to overdraw a user account even if application checks are bypassed"() {
        given:
        def alice = fundedAccount('EUR', 5.00)

        when:
        database.dsl().execute("UPDATE accounts SET balance = balance - 10 WHERE id = '${alice.id()}'")

        then:
        thrown(org.jooq.exception.DataAccessException)
        balance(alice.id()) == 5.00
    }

    def "lists an account's entries newest first"() {
        given:
        def alice = fundedAccount('EUR', 100.00)
        def bob = fundedAccount('EUR', 0)
        transfer(alice.id(), bob.id(), 1.00)
        transfer(alice.id(), bob.id(), 2.00)

        expect:
        ledger.entries(alice.id(), 10)*.amount() == [-2.00, -1.00, 100.00]
        ledger.entries(alice.id(), 1).size() == 1
    }

    def "loads a stored transaction with its entries in order"() {
        given:
        def eur = fundedAccount('EUR', 20.00)
        def gbp = fundedAccount('GBP', 0)
        def created = transfer(eur.id(), gbp.id(), 20.00)

        when:
        def loaded = ledger.getTransaction(created.id())

        then:
        loaded.id() == created.id()
        loaded.type() == TransactionType.TRANSFER
        loaded.sourceAmount() == 20.00
        loaded.destinationAmount() == created.destinationAmount()
        loaded.fxRate() == created.fxRate()
        loaded.createdAt() == created.createdAt()
        loaded.entries()*.id() == created.entries()*.id()
        loaded.entries()*.amount() == created.entries()*.amount()
    }

    def "reports unknown transactions as not found"() {
        when:
        ledger.getTransaction(UUID.randomUUID())

        then:
        thrown(NotFoundException)
    }

    def "reconciliation is balanced after mixed-currency activity"() {
        given:
        def eur = fundedAccount('EUR', 500.00)
        def usd = fundedAccount('USD', 300.00)
        def pln = fundedAccount('PLN', 0)
        transfer(eur.id(), usd.id(), 123.45)
        transfer(usd.id(), pln.id(), 99.99)
        transfer(usd.id(), eur.id(), 0.01)

        when:
        def report = ledger.reconcile()

        then:
        report.balanced()
        report.mismatchedAccounts().isEmpty()
        report.netByCurrency().keySet() == ['EUR', 'PLN', 'USD'] as Set
    }

    def "reconciliation flags a materialised balance that drifted from the journal"() {
        given:
        def alice = fundedAccount('EUR', 10.00)
        database.dsl().execute("UPDATE accounts SET balance = 11 WHERE id = '${alice.id()}'")

        when:
        def report = ledger.reconcile()

        then:
        !report.balanced()
        report.mismatchedAccounts() == [alice.id()]
    }
}
