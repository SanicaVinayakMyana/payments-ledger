package com.sanica.ledger.idempotency

import com.sanica.ledger.api.Json
import java.time.Duration
import spock.lang.Specification

class RedisOutageSpec extends Specification {

    def "an unreachable Redis degrades to a cache miss instead of failing the request"() {
        given: 'nothing listens on port 1'
        def cache = new RedisResponseCache('redis://localhost:1', Json.mapper(), Duration.ofMinutes(5))

        when:
        cache.put('k1', new StoredResponse('abc', 201, '{}'))
        def result = cache.get('k1')

        then:
        noExceptionThrown()
        result.isEmpty()

        cleanup:
        cache.close()
    }
}
