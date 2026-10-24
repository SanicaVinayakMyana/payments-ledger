package com.sanica.ledger.idempotency

import com.sanica.ledger.api.Json
import com.sanica.ledger.support.TestDatabase
import java.time.Duration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

@Requires({ TestDatabase.dockerAvailable() })
class RedisResponseCacheSpec extends Specification {

    @Shared
    GenericContainer redis

    @Shared
    RedisResponseCache cache

    def setupSpec() {
        redis = new GenericContainer(DockerImageName.parse('redis:7-alpine')).withExposedPorts(6379)
        redis.start()
        cache = new RedisResponseCache("redis://${redis.host}:${redis.getMappedPort(6379)}", Json.mapper(),
                Duration.ofMinutes(5))
    }

    def cleanupSpec() {
        cache?.close()
        redis?.stop()
    }

    def "stores and returns responses"() {
        given:
        def response = new StoredResponse('abc', 201, '{"id":"1"}')

        when:
        cache.put('k1', response)

        then:
        cache.get('k1') == Optional.of(response)
        cache.get('missing') == Optional.empty()
    }
}
