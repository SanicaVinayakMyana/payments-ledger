package com.sanica.ledger.fx

import com.sanica.ledger.domain.ValidationException
import spock.lang.Specification
import spock.lang.Subject

class StaticFxRateProviderSpec extends Specification {

    @Subject
    def fx = new StaticFxRateProvider()

    def "converting a currency to itself is exactly 1"() {
        expect:
        fx.rate('GBP', 'GBP') == 1
    }

    def "#from -> #to is #expected (derived from EUR reference rates, 8 dp, half-even)"() {
        expect:
        fx.rate(from, to) == new BigDecimal(expected)

        where:
        from  | to    || expected
        'EUR' | 'USD' || '1.08000000'
        'USD' | 'EUR' || '0.92592593'
        'GBP' | 'USD' || '1.27058824'
        'INR' | 'JPY' || '1.79005525'
    }

    def "rejects unsupported currencies"() {
        when:
        fx.rate('EUR', 'BTC')

        then:
        thrown(ValidationException)
    }
}
