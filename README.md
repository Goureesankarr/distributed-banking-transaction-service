# Distributed Banking Transaction Service

[![CI](https://github.com/Goureesankarr/distributed-banking-transaction-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Goureesankarr/distributed-banking-transaction-service/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)](https://spring.io/projects/spring-boot)

A Spring Boot service that moves money between accounts correctly under concurrency.

The interesting parts are concurrency control, idempotent retries, and getting events
onto Kafka without a dual write.

**Java 21, Spring Boot 3.3, PostgreSQL, Redis, Kafka, Docker, Testcontainers, Prometheus + Grafana**

## What it does

| Capability | How |
|---|---|
| Account management | Open accounts, list them, freeze/close via admin API |
| Money transfer | `POST /api/v1/transfers`, validated and authorised per account |
| Idempotent transactions | `Idempotency-Key` header, claim row with a unique index, stored response replay |
| Concurrency safety | JPA `@Version` optimistic locking with a bounded, jittered retry loop |
| Transaction history | Paged, date-filtered, per account |
| Double-entry ledger | Two balanced legs per transfer, reconcilable independently of balances |
| Event streaming | Transactional outbox -> Kafka, at-least-once, idempotent consumer, DLT |
| Rate limiting | Redis token bucket in a Lua script, separate budget for money movement |
| Caching | Redis read-through cache for account lookups, evicted after commit |
| Auth | Stateless JWT (HS256), BCrypt, role-based access |
| Audit logs | Append-only trail written in its own transaction so rejections survive rollback |
| Metrics | Micrometer -> Prometheus -> provisioned Grafana dashboard |

## Architecture

A request path, and the relay that runs behind it:

```
client
  -> JwtAuthenticationFilter
  -> RateLimitFilter          Redis token bucket, Lua script
  -> TransferController
       -> TransferService     idempotency claim, retry loop, audit
            -> TransferExecutor    one transaction per attempt:
                                   debit + credit + two ledger legs + outbox row
                 -> PostgreSQL     Redis account cache evicted after commit

OutboxPublisher    polls outbox_event every 500 ms, FOR UPDATE SKIP LOCKED
  -> Kafka
       -> TransactionEventConsumer   -> audit, metrics
                                     -> poison messages to the DLT
```

## Design notes

### Optimistic locking, not row locks

`account.version` is a JPA `@Version` column. Nothing is locked while a transfer is
computed; the `UPDATE` carries `WHERE version = ?` and a loser gets
`OptimisticLockingFailureException`.

The retry loop lives in `TransferService`, *outside* the transaction, because a retry
needs a fresh transaction and a fresh persistence context, and retrying inside the failed
transaction would just replay stale state. Backoff is randomised so a burst of writers on
one hot account doesn't retry in lockstep. After five contended attempts the caller gets
`409` and can retry with the same idempotency key.

`TransferExecutor` also flushes the two account updates in account-id order, so an A->B
transfer and a concurrent B->A transfer can't grab each other's row locks and deadlock.

Covered by `TransferConcurrencyIT`: 20 threads, one source account, exact final balances,
and a second test where ten writers compete for three transfers' worth of funds and the
balance floor holds at zero.

### Idempotency

`POST /api/v1/transfers` requires an `Idempotency-Key`.

* The `(idempotency_key, username)` unique index is the concurrency primitive: two
  simultaneous retries race to `INSERT` a claim, exactly one wins, the loser gets `409`.
* When the winner finishes, the response it sent is stored on the claim. Later retries
  replay those exact bytes (`Idempotent-Replay: true`).
* Reusing a key with a *different* body is `422`, not a silent wrong replay. The request
  is hashed with SHA-256 and compared.
* Deterministic business rejections (insufficient funds) are bound to the key too.
  Transient failures (lock contention) release the key, because pinning a key to an
  error the request didn't really cause would be wrong.

### Transactional outbox instead of a dual write

Publishing to Kafka inside the transfer transaction would mean a transfer that commits
without its event, or an event for a transfer that rolled back. Instead the event is
written to `outbox_event` in the *same* transaction, and `OutboxPublisher` relays it
afterwards. Rows are claimed with `FOR UPDATE SKIP LOCKED`, so extra instances share the
work rather than duplicating it.

Delivery is therefore at-least-once. `TransactionEventConsumer` checks the event id
before doing any work, which makes redelivery a no-op, and poison messages go to a
dead-letter topic instead of blocking the partition forever.

### Rate limiting

A token bucket implemented as a single Lua script, so check-and-decrement is atomic
inside Redis and every instance shares one budget per principal. Money movement gets a
tighter bucket than reads.

If Redis is unreachable the limiter fails open. Rejecting live banking traffic
because a cache node is down turns a degraded dependency into an outage. Fail-open events
are counted (`banking_ratelimit_failed_open_total`) so they're visible in Grafana.

## Running it

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Prometheus | http://localhost:9090 |
| Grafana (admin/admin) | http://localhost:3000 |

To get an admin account, set `BOOTSTRAP_ADMIN_PASSWORD` before starting. There is
deliberately no default admin credential.

### A transfer, end to end

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct-horse-battery","fullName":"Alice"}' \
  | jq -r .accessToken)
```

```bash
curl -s -X POST localhost:8080/api/v1/accounts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"currency":"USD","openingBalance":1000.00}'
```

```bash
curl -s -X POST localhost:8080/api/v1/transfers -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"sourceAccountNumber":"ACC...","targetAccountNumber":"ACC...","amount":125.00,"currency":"USD","description":"rent"}'
```

Send the same request with the same `Idempotency-Key` again: the response is identical
and the balances don't move.

```bash
curl -s "localhost:8080/api/v1/accounts/ACC.../transfers?page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

## Tests

```bash
mvn test
```

Unit tests only: domain invariants, the retry loop (via mocks), request hashing. No
Docker required.

```bash
mvn verify
```

Adds the Testcontainers suite against real Postgres, Redis and Kafka:

| Test | Covers |
|---|---|
| `TransferConcurrencyIT` | No lost updates under 20 concurrent writers; no overdraw under contention; ledger nets to zero |
| `IdempotencyIT` | Byte-identical replay, key-reuse rejection, rejections bound to their key |
| `EventStreamIT` | Outbox drains to Kafka and the consumer processes exactly once |
| `RateLimitIT` | Transfer bucket throttles independently of the read bucket |
| `SecurityIT` | Anonymous, forged-token, cross-customer and privilege-escalation attempts |

The build pins the Docker API version (`-Dapi.version=1.43`) because the docker-java
client bundled with Testcontainers negotiates 1.32 by default, which Docker Engine 29+
rejects outright. On a daemon older than Docker 24, override it:
`mvn verify -Ddocker.api.version=1.41`.

Using Colima instead of Docker Desktop, export these first:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" && export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Observability

`/actuator/prometheus` exposes, alongside the standard JVM and HTTP metrics:

| Metric | Notes |
|---|---|
| `banking_transfer_duration_seconds` | p50/p95/p99 transfer latency |
| `banking_transfer_completed_total` | Throughput |
| `banking_transfer_failed_total{reason}` | Rejections split by cause |
| `banking_transfer_optimistic_retries_total` | Contention; sustained growth means a hot account |
| `banking_transfer_idempotent_replays_total` | How often clients are retrying |
| `banking_outbox_pending` | Events committed but not yet on the broker; should hover near zero |
| `banking_ratelimit_throttled_total` | Clients hitting 429 |
| `banking_events_consumed_total{type}` | Consumer keeping up |

`GET /api/v1/admin/reconciliation` (admin only) sums every ledger entry: anything other
than zero means money was created or destroyed.

The Grafana dashboard in `ops/grafana/dashboards/` is provisioned automatically.

## Configuration

Everything below is environment-overridable; defaults suit a laptop.

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost:6379` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `JWT_SECRET` | dev-only base64 key | Must be replaced outside local dev; 256 bits or more |
| `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` | unset | No admin is created unless the password is set |
| `banking.rate-limit.*` | 100/min reads, 20/min transfers | Per principal |
| `banking.idempotency.ttl` | 24h | Replay window |
| `banking.outbox.poll-interval-ms` | 500 | Relay cadence |

## Known limits

* **Single currency per transfer.** Cross-currency movement would need an FX rate source
  and a spread/rounding policy; the service rejects mismatched currencies rather than
  guessing.
* **No deposits or withdrawals.** Accounts are funded at opening. External settlement
  rails are out of scope.
* **Rate limits are per principal, not per tenant or global.** A distributed quota across
  many customers would need a different bucket key.
* **The outbox relay is at-least-once.** Consumers must stay idempotent; exactly-once
  would require Kafka transactions across the consume-process-produce cycle.
