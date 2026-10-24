# Load test results

Measured with [`TransferSimulation`](../src/gatling/java/ledger/TransferSimulation.java) (Gatling 3.12) on 2026-10-24.

**Environment:** one laptop running the load generator, the API (Java 21, virtual threads, Hikari pool = 20)
and PostgreSQL (embedded, started by `./gradlew runLocal`) at the same time. Intel Core Ultra 7 255H
(16 cores), 16 GB RAM, Windows 11. No Redis. These numbers are a local baseline, not a production benchmark:
the load generator competes with the server and database for the same CPU.

Every request is a real write: each transfer has a fresh `Idempotency-Key` and goes through
the idempotency insert, the row locks, the journal insert and both balance updates.

## Run 1: paced at a 1,000 req/s target (latency at a known load)

```
./gradlew gatlingRun --simulation ledger.TransferSimulation -Dusers=50 -DtargetRps=1000 -DdurationSeconds=60
```

| Metric | Value |
|---|---|
| Requests | 54,042 (0 failed) |
| Mean throughput | **818.8 req/s** |
| p50 / p75 / p95 / p99 | 5 / 7 / **17** / **56** ms |
| Max | 157 ms |

## Run 2: unpaced (maximum throughput)

```
./gradlew gatlingRun --simulation ledger.TransferSimulation -Dusers=50 -DtargetRps=0 -DdurationSeconds=60
```

| Metric | Value |
|---|---|
| Requests | 350,831 (0 failed) |
| Mean throughput | **5,315.6 req/s** |
| p50 / p75 / p95 / p99 | 6 / 8 / 15 / 78 ms |
| Max | 1,110 ms |

## Integrity after load

Straight after the unpaced run (525,165 transfers written to the same database across two unpaced runs),
`GET /admin/reconciliation` returned:

```json
{"balanced":true,"netByCurrency":{"EUR":"0.00"},"mismatchedAccounts":[]}
```

## Notes

- The p99 tail is dominated by the shared-machine setup (GC pauses and CPU contention with the load generator).
  Running Gatling from a separate machine against `docker compose up` is the next step for a cleaner number.
- The simulation's pass criteria are failures < 1%, p95 < 50 ms and p99 < 100 ms for `transfer`.
