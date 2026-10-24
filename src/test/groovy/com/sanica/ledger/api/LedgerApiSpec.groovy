package com.sanica.ledger.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.sanica.ledger.Application
import com.sanica.ledger.idempotency.NoopResponseCache
import com.sanica.ledger.support.LedgerSpecification
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import spock.lang.Shared

/** Drives the real HTTP server end to end. */
class LedgerApiSpec extends LedgerSpecification {

    @Shared
    Application app = Application.start(0, database, new NoopResponseCache())

    @Shared
    HttpClient http = HttpClient.newHttpClient()

    @Shared
    ObjectMapper mapper = Json.mapper()

    def cleanupSpec() {
        app.close()
    }

    def "health check"() {
        expect:
        get('/health').body == [status: 'UP']
    }

    def "full payment flow: open accounts, deposit, convert and transfer, reconcile"() {
        when:
        def alice = post('/accounts', [ownerId: 'alice', currency: 'EUR'])
        def bob = post('/accounts', [ownerId: 'bob', currency: 'USD'])
        def topUp = post('/deposits', [accountId: alice.body.id, amount: '500.00'], 'deposit-1')
        def payment = post('/transfers', [fromAccountId: alice.body.id, toAccountId: bob.body.id, amount: '100.00'],
                'transfer-1')

        then:
        alice.status == 201
        alice.body.balance == '0.00'
        topUp.status == 201
        payment.status == 201
        payment.headers.firstValue('Idempotent-Replayed').get() == 'false'
        payment.body.type == 'TRANSFER'
        payment.body.sourceAmount == '100.00'
        payment.body.destinationAmount == '108.00'
        payment.body.fxRate == '1.08000000'
        payment.body.entries*.amount == ['-100.00', '100.00', '-108.00', '108.00']

        and:
        get("/accounts/${alice.body.id}").body.balance == '400.00'
        get("/accounts/${bob.body.id}").body.balance == '108.00'
        get("/transactions/${payment.body.id}").body.entries.size() == 4
        get("/accounts/${alice.body.id}/entries?limit=1").body*.amount == ['-100.00']

        and:
        def reconciliation = get('/admin/reconciliation').body
        reconciliation.balanced == true
        reconciliation.netByCurrency == [EUR: '0.00', USD: '0.00']
    }

    def "retrying with the same Idempotency-Key replays the original response"() {
        given:
        def alice = account('EUR', '50.00')
        def bob = account('EUR', null)
        def body = [fromAccountId: alice, toAccountId: bob, amount: '20.00']

        when:
        def first = post('/transfers', body, 'retry-me')
        def retry = post('/transfers', body, 'retry-me')

        then:
        retry.status == 201
        retry.headers.firstValue('Idempotent-Replayed').get() == 'true'
        retry.body == first.body
        get("/accounts/${alice}").body.balance == '30.00'
    }

    def "reusing an Idempotency-Key for a different request is rejected"() {
        given:
        def alice = account('EUR', '50.00')
        def bob = account('EUR', null)
        post('/transfers', [fromAccountId: alice, toAccountId: bob, amount: '20.00'], 'same-key')

        when:
        def response = post('/transfers', [fromAccountId: alice, toAccountId: bob, amount: '25.00'], 'same-key')

        then:
        response.status == 422
        response.body.error == 'IDEMPOTENCY_KEY_REUSED'
        get("/accounts/${alice}").body.balance == '30.00'
    }

    def "a transfer without an Idempotency-Key is rejected"() {
        given:
        def alice = account('EUR', '50.00')
        def bob = account('EUR', null)

        when:
        def response = post('/transfers', [fromAccountId: alice, toAccountId: bob, amount: '1.00'], null)

        then:
        response.status == 400
        response.body.error == 'VALIDATION_ERROR'
    }

    def "insufficient funds return 422"() {
        given:
        def alice = account('EUR', '5.00')
        def bob = account('EUR', null)

        when:
        def response = post('/transfers', [fromAccountId: alice, toAccountId: bob, amount: '5.01'], 'too-much')

        then:
        response.status == 422
        response.body.error == 'INSUFFICIENT_FUNDS'
    }

    def "returns #status for #scenario"() {
        when:
        def response = request(method, path, body)

        then:
        response.status == status
        response.body.error == error

        where:
        scenario                 | method | path                                    | body                                  || status | error
        'malformed JSON'         | 'POST' | '/accounts'                             | '{not json'                           || 400    | 'VALIDATION_ERROR'
        'unknown JSON fields'    | 'POST' | '/accounts'                             | '{"ownerId":"a","currency":"EUR","x":1}' || 400 | 'VALIDATION_ERROR'
        'an unsupported currency'| 'POST' | '/accounts'                             | '{"ownerId":"a","currency":"XYZ"}'    || 400    | 'VALIDATION_ERROR'
        'a non-UUID id'          | 'GET'  | '/accounts/not-a-uuid'                  | null                                  || 400    | 'VALIDATION_ERROR'
        'an unknown account'     | 'GET'  | "/accounts/${UUID.randomUUID()}"        | null                                  || 404    | 'NOT_FOUND'
        'an unknown transaction' | 'GET'  | "/transactions/${UUID.randomUUID()}"    | null                                  || 404    | 'NOT_FOUND'
        'an out-of-range limit'  | 'GET'  | "/accounts/${UUID.randomUUID()}/entries?limit=0"   | null                       || 400    | 'VALIDATION_ERROR'
        'a non-numeric limit'    | 'GET'  | "/accounts/${UUID.randomUUID()}/entries?limit=abc" | null                       || 400    | 'VALIDATION_ERROR'
        'an unknown FX pair'     | 'GET'  | '/fx/rate?from=EUR&to=BTC'              | null                                  || 400    | 'VALIDATION_ERROR'
    }

    def "quotes FX rates"() {
        expect:
        get('/fx/rate?from=EUR&to=GBP').body == [from: 'EUR', to: 'GBP', rate: '0.85000000']
    }

    def "lists user accounts newest first, without system accounts"() {
        given:
        def first = account('EUR', null)
        def second = account('GBP', null)

        when:
        def accounts = get('/accounts').body

        then:
        accounts*.id == [second, first]
        accounts.every { it.kind == 'USER' }
        get('/accounts?limit=1').body*.id == [second]
    }

    def "lists recent transactions newest first, each with its journal entries"() {
        given:
        def alice = account('EUR', '50.00')
        def bob = account('USD', null)
        def payment = post('/transfers', [fromAccountId: alice, toAccountId: bob, amount: '10.00'], 'listed').body

        when:
        def transactions = get('/transactions?limit=10').body

        then:
        transactions*.type == ['TRANSFER', 'DEPOSIT']
        transactions[0].id == payment.id
        transactions[0].entries*.amount == ['-10.00', '10.00', '-10.80', '10.80']
        transactions[1].entries.size() == 2
        get('/transactions?limit=0').status == 400
    }

    def "serves the dashboard at the root"() {
        when:
        def response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}/")).build(),
                HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.headers().firstValue('Content-Type').get().startsWith('text/html')
        response.body().contains('<title>Payments Ledger</title>')
    }

    private String account(String currency, String funding) {
        def id = post('/accounts', [ownerId: 'owner', currency: currency]).body.id as String
        if (funding) {
            post('/deposits', [accountId: id, amount: funding], UUID.randomUUID().toString())
        }
        id
    }

    private Map get(String path) {
        request('GET', path, null)
    }

    private Map post(String path, Map body, String idempotencyKey = null) {
        request('POST', path, mapper.writeValueAsString(body), idempotencyKey)
    }

    private Map request(String method, String path, String body, String idempotencyKey = null) {
        def builder = HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}${path}"))
                .header('Content-Type', 'application/json')
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        if (idempotencyKey) {
            builder.header('Idempotency-Key', idempotencyKey)
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        [status: response.statusCode(), headers: response.headers(),
         body  : response.body() ? mapper.readValue(response.body(), Object) : null]
    }
}
