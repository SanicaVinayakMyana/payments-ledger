package com.sanica.ledger.domain

import spock.lang.Specification

class CurrenciesSpec extends Specification {

    def "normalises #amount #currency to the currency's minor-unit scale"() {
        expect:
        Currencies.requirePositiveAmount(new BigDecimal(amount), currency).toPlainString() == expected

        where:
        amount   | currency || expected
        '10'     | 'EUR'    || '10.00'
        '10.5'   | 'USD'    || '10.50'
        '0.01'   | 'GBP'    || '0.01'
        '1500'   | 'JPY'    || '1500'
        '1500.0' | 'JPY'    || '1500'
    }

    def "rejects #description"() {
        when:
        Currencies.requirePositiveAmount(amount == null ? null : new BigDecimal(amount), currency)

        then:
        thrown(ValidationException)

        where:
        amount           | currency | description
        null             | 'EUR'    | 'a missing amount'
        '0'              | 'EUR'    | 'a zero amount'
        '-5.00'          | 'EUR'    | 'a negative amount'
        '0.001'          | 'EUR'    | 'sub-cent precision'
        '1.5'            | 'JPY'    | 'fractional yen'
        '1000000000.01'  | 'EUR'    | 'an amount above the per-movement limit'
        '10'             | 'XYZ'    | 'an unsupported currency'
        '10'             | null     | 'a missing currency'
    }

    def "formats stored amounts without rounding"() {
        expect:
        Currencies.format(new BigDecimal('10.5000'), 'EUR') == '10.50'
        Currencies.format(new BigDecimal('1620.0000'), 'JPY') == '1620'
    }
}
