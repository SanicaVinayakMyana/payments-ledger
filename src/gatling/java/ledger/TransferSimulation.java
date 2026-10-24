package ledger;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.UUID;

/**
 * Each virtual user opens two EUR accounts, funds one, then sends transfers for the test duration.
 * Every transfer carries a fresh Idempotency-Key, so each request is a real write.
 *
 * <p>{@code targetRps} paces the users to a fixed aggregate rate (latency at a known load);
 * {@code targetRps=0} removes pacing to find maximum throughput.
 *
 * <pre>./gradlew gatlingRun --simulation ledger.TransferSimulation -DbaseUrl=http://localhost:8080 -Dusers=50 -DtargetRps=1000 -DdurationSeconds=60</pre>
 */
public class TransferSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final int USERS = Integer.getInteger("users", 50);
    private static final int TARGET_RPS = Integer.getInteger("targetRps", 1000);
    private static final Duration DURATION = Duration.ofSeconds(Long.getLong("durationSeconds", 60));

    private static final ChainBuilder TRANSFER = exec(http("transfer").post("/transfers")
            .header("Idempotency-Key", "#{randomUuid()}")
            .body(StringBody("{\"fromAccountId\":\"#{from}\",\"toAccountId\":\"#{to}\",\"amount\":\"1.00\"}"))
            .check(status().is(201)));

    private final HttpProtocolBuilder protocol = http
            .baseUrl(BASE_URL)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private final ScenarioBuilder transfers = scenario("Transfers")
            .exec(session -> session.set("owner", "load-" + UUID.randomUUID()))
            .exec(http("open account").post("/accounts")
                    .body(StringBody("{\"ownerId\":\"#{owner}\",\"currency\":\"EUR\"}"))
                    .check(status().is(201), jsonPath("$.id").saveAs("from")))
            .exec(http("open account").post("/accounts")
                    .body(StringBody("{\"ownerId\":\"#{owner}\",\"currency\":\"EUR\"}"))
                    .check(status().is(201), jsonPath("$.id").saveAs("to")))
            .exec(http("deposit").post("/deposits")
                    .header("Idempotency-Key", "#{randomUuid()}")
                    .body(StringBody("{\"accountId\":\"#{from}\",\"amount\":\"1000000.00\"}"))
                    .check(status().is(201)))
            .during(DURATION).on(TARGET_RPS > 0
                    ? pace(Duration.ofNanos(1_000_000_000L * USERS / TARGET_RPS)).exec(TRANSFER)
                    : TRANSFER);

    {
        setUp(transfers.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(5))))
                .protocols(protocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        details("transfer").responseTime().percentile(95.0).lt(50),
                        details("transfer").responseTime().percentile(99.0).lt(100));
    }
}
