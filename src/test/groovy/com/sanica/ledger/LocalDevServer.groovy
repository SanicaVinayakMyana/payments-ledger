package com.sanica.ledger

import com.sanica.ledger.idempotency.NoopResponseCache
import com.sanica.ledger.support.TestDatabase

/** `./gradlew runLocal`: the real API on a throwaway PostgreSQL, for manual testing and load tests. */
class LocalDevServer {

    static void main(String[] args) {
        int port = (System.getenv('PORT') ?: '8080') as int
        def app = Application.start(port, TestDatabase.get(), new NoopResponseCache())
        Runtime.runtime.addShutdownHook(new Thread({ app.close() } as Runnable))
        println "Payments ledger listening on http://localhost:${app.port()} (Ctrl+C to stop)"
        Thread.currentThread().join()
    }
}
