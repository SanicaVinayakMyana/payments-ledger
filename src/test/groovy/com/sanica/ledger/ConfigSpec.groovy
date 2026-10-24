package com.sanica.ledger

import spock.lang.Specification

class ConfigSpec extends Specification {

    def "falls back to local defaults"() {
        when:
        def config = Config.fromMap([:])

        then:
        config.port() == 8080
        config.jdbcUrl() == 'jdbc:postgresql://localhost:5432/ledger'
        config.dbPoolSize() == 20
        config.redisUrl() == null
    }

    def "reads every setting from the environment"() {
        when:
        def config = Config.fromMap([PORT: '9000', DB_URL: 'jdbc:postgresql://db/ledger', DB_USER: 'u',
                                     DB_PASSWORD: 'p', DB_POOL_SIZE: '8', REDIS_URL: 'redis://cache:6379'])

        then:
        config == new Config(9000, 'jdbc:postgresql://db/ledger', 'u', 'p', 8, 'redis://cache:6379')
    }

    def "treats a blank REDIS_URL as disabled"() {
        expect:
        Config.fromMap([REDIS_URL: ' ']).redisUrl() == null
    }
}
