# Paytm Wallet R2 — P2P Transfer Service

A small wallet service built for the Paytm PML R2 agentic exercise. The goal was not to build a large payments platform, but to implement the smallest design that is correct under concurrency, easy to run, and easy to explain.

The service supports wallet creation, balance lookup, peer-to-peer transfers, transfer lookup, idempotent retries, structured logs, metrics, and containerized deployment.

## Live API

**Base URL**

```text
https://paytm-wallet-r2-production.up.railway.app
```

Health check:

```bash
curl https://paytm-wallet-r2-production.up.railway.app/health
```

Expected response:

```json
{
  "status": "UP",
  "groups": ["liveness", "readiness"]
}
```

Metrics:

```text
https://paytm-wallet-r2-production.up.railway.app/metrics
```

## What the service supports

### Wallets

```http
POST /wallets
GET  /wallets/{id}
```

`POST /wallets` is a race-safe get-or-create operation for the authenticated user.

### Transfers

```http
POST /transfers
GET  /transfers/{id}
```

A transfer request contains:

```json
{
  "from_wallet_id": "uuid",
  "to_wallet_id": "uuid",
  "amount_paise": 500,
  "idempotency_key": "client-generated-key"
}
```

Money is always represented as integer paise. No floating-point type is used for balances or transfer amounts.

### Authentication

The exercise uses a simple bearer token to identify the caller:

```http
Authorization: Bearer <token>
```

The raw bearer token is never written to application logs.

## Design

The application is intentionally a modular monolith:

```text
Client
  |
  v
Spring Boot API
  |
  +-- Controller
  |      |
  |      v
  +-- Service
  |      |
  |      v
  +-- Repository
         |
         v
     PostgreSQL
```

The controller layer handles HTTP concerns, the service layer owns the business transaction, and the repository layer contains the SQL used to enforce database-level correctness.

### Main technologies

- Java 21
- Spring Boot
- Spring JDBC
- PostgreSQL
- Flyway
- Micrometer / Prometheus metrics
- Testcontainers
- Maven
- Docker / Docker Compose
- Railway
- Neon PostgreSQL

## Data model

The main tables are `wallets` and `transfers`.

### Wallet

Important fields:

```text
id
user_key
balance_paise
created_at
updated_at
```

Important constraints:

```text
PRIMARY KEY(id)
UNIQUE(user_key)
CHECK(balance_paise >= 0)
```

`UNIQUE(user_key)` makes wallet creation safe even when many requests for the same user arrive concurrently.

### Transfer

Important fields:

```text
id
idempotency_key
caller_user_key
from_wallet_id
to_wallet_id
amount_paise
status
decline_reason
created_at
updated_at
```

Important constraints include:

```text
UNIQUE(idempotency_key)
CHECK(amount_paise > 0)
CHECK(from_wallet_id <> to_wallet_id)
```

## Transfer correctness

The critical part of the exercise is the transfer path.

Each transfer runs inside one PostgreSQL transaction.

At a high level:

```text
validate request
      |
      v
BEGIN TRANSACTION
      |
      v
check existing idempotency result
      |
      v
lock participating wallets in deterministic order
      |
      v
claim idempotency key
      |
      v
debit source if balance is sufficient
      |
      v
credit destination
      |
      v
store final transfer status
      |
      v
COMMIT
```

### Why deterministic locking?

For concurrent transfers such as:

```text
A -> B
B -> A
```

both transactions acquire wallet locks in the same deterministic order instead of transfer-direction order.

That avoids circular lock acquisition and reduces deadlock risk.

### No overdraft

The source wallet is debited conditionally:

```sql
UPDATE wallets
SET balance_paise = balance_paise - ?
WHERE id = ?
  AND balance_paise >= ?
```

The database also has:

```sql
CHECK(balance_paise >= 0)
```

This gives two layers of protection against a negative balance.

### Conservation

Debit, credit, transfer status, and idempotency state are committed in the same database transaction.

If any step fails, the transaction rolls back.

This prevents partial money movement such as a debit being persisted without the corresponding credit.

## Idempotency

`idempotency_key` is protected by a database unique constraint.

For the same key and the same request body, the original transfer result is returned.

For the same key with a different request body, the service returns:

```text
409 Conflict
```

The idempotency record and the financial movement are committed together, which gives one financial effect for a given idempotency key even if the client retries after a lost HTTP response.

## Running locally

### Prerequisites

- Docker
- Docker Compose

Start the full application and PostgreSQL stack:

```bash
docker compose up --build -d
```

Check health:

```bash
curl http://localhost:8080/health
```

Check container status:

```bash
docker compose ps
```

Follow logs:

```bash
docker compose logs -f app
```

Stop the stack:

```bash
docker compose down
```

Reset the database as well:

```bash
docker compose down -v
```

## Running tests

The project uses Testcontainers so integration tests run against a real PostgreSQL instance rather than an in-memory substitute.

```bash
mvn test
```

## Concurrency burst test

The repository contains a burst script that exercises the main correctness requirements.

Against the deployed service:

```bash
python3 scripts/burst.py all \
  --base-url https://paytm-wallet-r2-production.up.railway.app
```

It verifies:

1. 50 concurrent wallet get-or-create requests return exactly one wallet.
2. 30 concurrent retries with the same idempotency key produce one financial effect.
3. 300 concurrent transfers preserve total balance and never produce a negative wallet.

Example successful run:

```text
[PASS] Health: UP

[PASS] Gate 1: 50 requests returned exactly 1 wallet

[PASS] Gate 2: one transfer; one debit/credit;
all responses identical; conflict=409

[PASS] Gate 3:
total 300000 -> 300000
succeeded=284
declined=16
no negatives

ALL REQUESTED PROBES PASSED
```

## Observability

The service exposes structured JSON logs with correlation IDs.

A single request can be followed through events such as:

```text
transfer_created
transfer_debited
transfer_credited
transfer_succeeded
transfer_declined
idempotent_replay_hit
```

The application also exposes Micrometer metrics through:

```text
/metrics
```

Examples include:

```text
wallet_transfers_total
wallet_transfers_succeeded_total
wallet_transfers_declined_insufficient_funds_total
wallet_transfers_idempotent_replays_total
wallet_transfers_idempotency_conflicts_total
```

HTTP request count, latency histograms, status codes, and Hikari connection-pool metrics are also exposed.

## Deployment

The application is packaged as a multi-stage Docker image and runs as a non-root user.

Production setup used for this exercise:

```text
Railway
  |
  | Spring Boot container
  v
Neon PostgreSQL
```

The Railway service and Neon database are deployed in Singapore to keep application-to-database latency low, which is especially important because wallet transfers hold database row locks for the duration of the transaction.

Flyway runs automatically at application startup and manages the database schema.

## Consistency choice

For financial state, I prefer consistency over availability.

If the database is unavailable or a safe transaction cannot be established, the service fails the request instead of acknowledging an uncertain payment and attempting to reconcile it later.

For this exercise, that is simpler and safer than introducing asynchronous money movement, queues, distributed transactions, or eventual consistency.

## What I intentionally did not add

I avoided adding infrastructure that was not required to satisfy the exercise:

- Kafka
- Redis
- CQRS
- event sourcing
- multiple microservices
- application-level distributed locks
- global `SERIALIZABLE` isolation

All of these can be valid in larger systems, but PostgreSQL transactions, deterministic row locking, conditional debit, and database constraints are enough for the required invariants here.

## AI usage

AI was used interactively for design exploration, implementation assistance, debugging, and review.

I reviewed the generated code, made the final design choices, ran the concurrency and deployment tests, investigated failures using database and connection-pool behavior, and accepted or changed suggestions based on observed results.

## Cost

The exercise was deployed using free-tier resources.

**Total cost for the submission: ₹0.**

## Additional design note

A short design summary covering the data model, concurrency mechanism, rejected alternatives, idempotency, consistency trade-offs, AI usage, and cost is available in:

```text
docs/Paytm Wallet R2 — One-Page Engineering Design.pdf
```
