package com.sanica.ledger.support

import com.sanica.ledger.db.Database
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * One real PostgreSQL per test JVM. Uses Testcontainers when Docker is available (CI) and falls back
 * to embedded PostgreSQL binaries otherwise, so the suite also runs on a laptop without Docker.
 */
final class TestDatabase {

    private static Database database

    private TestDatabase() {
    }

    static synchronized Database get() {
        if (database == null) {
            database = start()
        }
        database
    }

    static boolean dockerAvailable() {
        try {
            DockerClientFactory.instance().isDockerAvailable()
        } catch (Throwable ignored) {
            false
        }
    }

    static void reset(Database db) {
        db.dsl().execute('TRUNCATE ledger_entries, ledger_transactions, idempotency_keys, accounts CASCADE')
    }

    private static Database start() {
        if (dockerAvailable()) {
            def container = new PostgreSQLContainer('postgres:16-alpine')
            container.start()
            println "Test database: Testcontainers ${container.dockerImageName}"
            return Database.connect(container.jdbcUrl, container.username, container.password, 20)
        }
        def embedded = EmbeddedPostgres.builder().start()
        println 'Test database: embedded PostgreSQL (Docker not available)'
        def db = Database.connect(embedded.getJdbcUrl('postgres', 'postgres'), 'postgres', 'postgres', 20)
        Runtime.runtime.addShutdownHook(new Thread({
            db.close()
            embedded.close()
        } as Runnable))
        db
    }
}
